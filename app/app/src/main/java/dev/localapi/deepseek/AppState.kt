package dev.localapi.deepseek

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.localapi.deepseek.ui.AppPalette
import dev.localapi.deepseek.ui.AppThemeMode
import dev.localapi.deepseek.ui.KatexBitmapRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AppScreen { BOOT, ONBOARDING, TOKEN, CHAT, SETTINGS, TERMINAL }
enum class SettingsPane(val title: String) {
    ROOT("Настройки"),
    TOKEN("Авторизация и ключи"),
    SERVER("Локальный API"),
    TUNNEL("Cloudflare Tunnel"),
    SYSTEM("О приложении"),
    UI("Интерфейс")
}

data class WebSource(val title: String, val url: String, val site: String = "", val date: String = "")
data class UsageStats(val promptTokens: Long = 0, val completionTokens: Long = 0, val totalTokens: Long = 0)
data class BalanceInfo(val available: Boolean, val currency: String, val total: String, val granted: String, val toppedUp: String)

private val MSG_ID_SEQ = AtomicLong(1L)
fun nextMessageUid(): Long = MSG_ID_SEQ.getAndIncrement()

data class ChatMessage(
    val uid: Long = nextMessageUid(),
    val role: String,
    val content: String,
    val reasoning: String = "",
    val sources: List<WebSource> = emptyList(),
    val images: List<String> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    val usage: UsageStats? = null,
    val elapsedMs: Long = 0,
    val reasoningMs: Long = 0,
    val tokPerSec: Double = 0.0,
    val cancelled: Boolean = false,
    val error: String? = null,
    val hasToolCall: Boolean = false,
    val toolBlock: ToolBlockData? = null,
    val notice: SandboxNotice? = null,
    val estTokens: Int = if (role == "user" || role == "assistant") 4 + AgentDirectives.estimateTokens(content) + images.size * 256 else 0
)

data class ToolBlockData(
    val id: String,
    val label: String,
    val command: String,
    val output: String = "",
    val exitCode: String = "?",
    val running: Boolean = false
)

data class SandboxNotice(val text: String, val kind: String, val ts: Long = System.currentTimeMillis())

data class ChatSummaryItem(val id: String, val title: String, val updatedAt: Long, val messageCount: Int)
data class AgentFileItem(val name: String, val size: Long, val selected: Boolean = true)

data class SandboxState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val running: Boolean = false,
    val shellReady: Boolean = false,
    val baseInstalled: Boolean = false,
    val installingBase: Boolean = false,
    val installedTools: String = "",
    val networkStatus: String = "",
    val version: String = "",
    val ramMb: Int = 2048,
    val cores: Int = 2,
    val maxRamMb: Int = 2048,
    val maxCores: Int = 2,
    val log: String = "",
    val progressPercent: Int = -1
)

data class ServerInfo(val running: Boolean = false, val port: Int = 0, val baseUrl: String = "", val key: String = "")
data class TunnelInfo(val state: String = "stopped", val mode: String = "quick", val url: String = "", val error: String = "", val detail: String = "", val port: Int = 0)
data class UpdateManifest(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val notes: String)
data class PendingAttachment(val dataUrl: String, val preview: Bitmap)

class AppState(private val activity: Activity) {
    interface HostActions {
        fun pickImages()
        fun pickAgentZip()
        fun chooseAgentRoot()
        fun openHtmlPreview(html: String)
        fun finishApp()
    }

    private val prefs: SharedPreferences = activity.getSharedPreferences("ui_native", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val destroyed = AtomicBoolean(false)
    private val pollInFlight = AtomicBoolean(false)

    var hostActions: HostActions? = null
    var foreground: Boolean = true

    private val sandbox = SandboxManager(activity)
    private val updater = Updater(activity)
    private val tunnel = CloudflareTunnel.get(activity)
    private val browser = AgentBrowser(activity)

    // Synchronized LRU caches accessed from both UI (fast hit) and IO (decode on miss)
    private val cacheLock = Any()
    private val dataUrlImageCache = object : LinkedHashMap<String, ImageBitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 24
    }
    private val agentMediaCache = object : LinkedHashMap<String, List<Pair<String, ImageBitmap>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, ImageBitmap>>>?): Boolean = size > 16
    }

    // Navigation & overlays
    var screen by mutableStateOf(AppScreen.BOOT)
    var settingsPane by mutableStateOf(SettingsPane.ROOT)
    var settingsReturn by mutableStateOf(AppScreen.CHAT)
    var bootError by mutableStateOf<String?>(null)
    var toastMessage by mutableStateOf<String?>(null)

    var showModelSheet by mutableStateOf(false)
    var showToolsSheet by mutableStateOf(false)
    var showWorkspaceSheet by mutableStateOf(false)
    var showSandboxSheet by mutableStateOf(false)
    var showJournalSheet by mutableStateOf(false)
    var showUpdateDialog by mutableStateOf(false)
    var previewImageDataUrl by mutableStateOf<String?>(null)

    // Auth & Status
    var hasToken by mutableStateOf(false)
    var online by mutableStateOf(true)
    var activeMode by mutableStateOf("api")
    var authMode by mutableStateOf(prefs.getString("authMode", "api") ?: "api")
    var balance by mutableStateOf<BalanceInfo?>(null)
    var appVersion by mutableStateOf("")
    var notifGranted by mutableStateOf(false)
    var batteryIgnoring by mutableStateOf(false)

    // Token screen transient state
    var tokenChecking by mutableStateOf(false)
    var tokenError by mutableStateOf<String?>(null)
    var tokenVerified by mutableStateOf(false)

    // Appearance & AI Settings
    var themeMode by mutableStateOf(AppThemeMode.from(prefs.getString("theme", "system")))
    var palette by mutableStateOf(AppPalette.from(prefs.getString("palette", "dynamic")))
    var uiScale by mutableIntStateOf(prefs.getInt("uiScale", 100).coerceIn(55, 175))
    var textZoom by mutableIntStateOf(prefs.getInt("textZoom", 100).coerceIn(60, 200))
    var compact by mutableStateOf(prefs.getBoolean("compact", false))

    var model by mutableStateOf(prefs.getString("model", AppConfig.DEFAULT_MODEL_ID) ?: AppConfig.DEFAULT_MODEL_ID)
    var availableModels by mutableStateOf(listOf(AppConfig.DEFAULT_MODEL_ID, AppConfig.PRO_MODEL_ID))
    var thinking by mutableStateOf(prefs.getBoolean("thinking", true))
    var effort by mutableStateOf(prefs.getString("effort", "high") ?: "high")
    var search by mutableStateOf(prefs.getBoolean("search", false))
    var systemPrompt by mutableStateOf(prefs.getString("systemPrompt", "") ?: "")

    // Chat & Streaming
    var agentMode by mutableStateOf(false)
    var currentChatId by mutableStateOf("")
    var currentChatTitle by mutableStateOf("Новый чат")
    val chatList = mutableStateListOf<ChatSummaryItem>()
    val messages = mutableStateListOf<ChatMessage>()
    val pendingAttachments = mutableStateListOf<PendingAttachment>()
    var composerDraft by mutableStateOf("")

    var streaming by mutableStateOf(false)
    var streamStage by mutableStateOf<String?>(null)
    var streamReasoning by mutableStateOf("")
    var streamContent by mutableStateOf("")
    private var activeStreamId: Long = 0
    private var streamStartedMs: Long = 0
    private var firstTokenMs: Long = 0
    private var reasoningStartMs: Long = 0
    private var reasoningEndMs: Long = 0
    private var titleRequestedForChat: String = ""
    private var pollTask: Runnable? = null

    // Agent & Sandbox & Browser
    val agentFiles = mutableStateListOf<AgentFileItem>()
    val agentJournal = mutableStateListOf<String>()
    var agentRootConfigured by mutableStateOf(false)
    var agentRootName by mutableStateOf("")
    var sandboxState by mutableStateOf(SandboxState())
    var terminalOutput by mutableStateOf("")

    private var agentToolCount = 0
    private var agentBrowserCount = 0
    private var agentToolBusy = false
    private var lastExecCommand = ""
    private var repeatExecCount = 0
    private var activeToolId: String? = null
    private var activeToolChatId: String? = null
    private var activeToolBlockIndex: Int = -1

    // Server & Tunnel
    var serverInfo by mutableStateOf(ServerInfo())
    var serverPort by mutableIntStateOf(
        prefs.getInt("serverPort", AppConfig.DEFAULT_LOCAL_API_PORT)
            .coerceIn(AppConfig.MIN_LOCAL_API_PORT, AppConfig.MAX_LOCAL_API_PORT)
    )
    var cfMode by mutableStateOf(prefs.getString("cfMode", "quick") ?: "quick")
    var cfHost by mutableStateOf(prefs.getString("cfHost", "") ?: "")
    var cfToken by mutableStateOf("")
    var tunnelInfo by mutableStateOf(TunnelInfo())

    // Updater
    var updateManifest by mutableStateOf<UpdateManifest?>(null)
    var updateStatusText by mutableStateOf("Проверяются при запуске")
    var updateProgress by mutableIntStateOf(-1)
    var updateBusy by mutableStateOf(false)
    var updateError by mutableStateOf<String?>(null)
    var updateRemind by mutableStateOf(prefs.getBoolean("updateRemind", true))
    var updateInstalling by mutableStateOf(false)

    init {
        loadJournal()
        wireListeners()
    }

    private fun runIo(block: () -> Unit) {
        if (destroyed.get() || io.isShutdown) return
        try {
            io.execute(block)
        } catch (_: Exception) {
        }
    }

    private fun postMain(block: () -> Unit) {
        if (destroyed.get()) return
        main.post {
            if (!destroyed.get()) block()
        }
    }

    private fun wireListeners() {
        sandbox.setListener(object : SandboxManager.Listener {
            override fun onProgress(percent: Int) {
                sandboxState = sandboxState.copy(installing = true, progressPercent = percent)
                addJournal("sandbox", "Загрузка $percent%")
            }

            override fun onInstalled() {
                addJournal("sandbox", "Песочница установлена")
                sandboxState = sandboxState.copy(installing = false, installed = true, progressPercent = -1)
                refreshSandbox()
                showToast("Песочница установлена")
            }

            override fun onError(message: String, chatId: String) {
                addJournal("sandbox", "Ошибка: $message")
                sandboxState = sandboxState.copy(installing = false, progressPercent = -1)
                refreshSandbox()
                showToast(message)
                appendNotice(chatId, "Ошибка песочницы: $message", "error")
            }

            override fun onStarted(chatId: String) {
                addJournal("sandbox", "QEMU запущен")
                refreshSandbox()
                appendNotice(chatId, "Песочница запущена", "started")
            }

            override fun onOutput(text: String) {
                val clean = text.lines().filterNot {
                    it.contains("__LOCALAPI_BEGIN_") || it.contains("__LOCALAPI_END_") || it.startsWith("# printf '")
                }.joinToString("\n")
                if (clean.isNotEmpty()) {
                    terminalOutput = (terminalOutput + clean).takeLast(200_000)
                    val cur = sandboxState.log
                    sandboxState = sandboxState.copy(log = (cur + clean).takeLast(64_000))
                }
            }

            override fun onExecChunk(id: String, chunk: String) {
                if (id != activeToolId || activeToolBlockIndex !in messages.indices) return
                val msg = messages[activeToolBlockIndex]
                val tb = msg.toolBlock ?: return
                messages[activeToolBlockIndex] = msg.copy(toolBlock = tb.copy(output = chunk, running = true))
            }

            override fun onExecResult(id: String, output: String, code: Int) {
                if (id != activeToolId) return
                val chatId = activeToolChatId
                val chatTitle = currentChatTitle
                val idx = activeToolBlockIndex
                activeToolId = null
                activeToolChatId = null
                activeToolBlockIndex = -1
                agentToolBusy = false
                if (idx in messages.indices) {
                    val msg = messages[idx]
                    val tb = msg.toolBlock
                    if (tb != null) {
                        messages[idx] = msg.copy(
                            toolBlock = tb.copy(
                                output = output.ifEmpty { "(пусто)" },
                                exitCode = code.toString(),
                                running = false
                            )
                        )
                    }
                }
                addJournal("agent", "exec result $code: ${output.take(400)}")
                if (agentMode && chatId == currentChatId) {
                    if (!chatId.isNullOrEmpty()) {
                        runIo {
                            SafAgentWorkspace.syncToSaf(activity, chatId, chatTitle)
                            postMain { refreshAgentFiles() }
                        }
                    } else {
                        refreshAgentFiles()
                    }
                    continueAgentWithTool(output.ifEmpty { "(пусто)" }, code.toString(), emptyList())
                }
            }

            override fun onExit(code: Int, chatId: String) {
                if (activeToolBlockIndex in messages.indices) {
                    val msg = messages[activeToolBlockIndex]
                    val tb = msg.toolBlock
                    if (tb != null && tb.running) {
                        messages[activeToolBlockIndex] = msg.copy(
                            toolBlock = tb.copy(output = "Песочница остановлена", exitCode = code.toString(), running = false)
                        )
                    }
                }
                activeToolId = null
                activeToolBlockIndex = -1
                agentToolBusy = false
                addJournal("sandbox", "QEMU завершился, код $code")
                val label = if (code == 0) "Песочница остановлена" else "Песочница остановлена (код $code)"
                appendNotice(chatId, label, if (code == 0) "stopped" else "error")
                refreshSandbox()
            }

            override fun onStatusChanged() {
                refreshSandbox()
            }
        })

        updater.setListener(object : Updater.Listener {
            override fun onUpdateAvailable(manifestJson: String, manual: Boolean) {
                val m = parseManifest(manifestJson) ?: return
                updateManifest = m
                updateStatusText = "Доступно ${m.versionName}"
                if (manual || updateRemind) {
                    updateBusy = false
                    updateError = null
                    updateProgress = -1
                    updateInstalling = false
                    showUpdateDialog = true
                }
            }

            override fun onUpdateNone(manual: Boolean) {
                updateStatusText = "У вас последняя версия"
                if (manual) showToast("У вас последняя версия${if (appVersion.isNotEmpty()) " (v$appVersion)" else ""}")
            }

            override fun onUpdateError(message: String) {
                updateStatusText = message
                updateBusy = false
                if (showUpdateDialog) updateError = message else showToast(message)
            }

            override fun onUpdateProgress(percent: Int) {
                updateProgress = percent.coerceIn(0, 100)
            }

            override fun onUpdateStatus(state: String) {
                when (state) {
                    "permission" -> {
                        updateBusy = false
                        updateProgress = -1
                        updateError = "Разрешите установку из неизвестных источников, затем вернитесь в приложение."
                    }
                    "installing" -> {
                        updateBusy = false
                        updateProgress = 100
                        updateInstalling = true
                        updateError = null
                    }
                    "installed" -> {
                        updateBusy = false
                        updateInstalling = false
                        showUpdateDialog = false
                        showToast("Обновление установлено")
                    }
                }
            }

            override fun onShowPendingUpdate() {
                val pending = parseManifest(updater.pending()) ?: return
                updateManifest = pending
                updateError = null
                updateProgress = -1
                showUpdateDialog = true
            }
        })

        tunnel.setListener { st ->
            tunnelInfo = st.toUiModel()
        }
    }

    fun boot() {
        try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            appVersion = info.versionName ?: ""
        } catch (_: Exception) { }

        val filesPath = activity.filesDir.absolutePath
        runIo {
            val openRes = parseEnvelope(Rust.nativeOpen(filesPath))
            val openData = openRes.data as? JSONObject
            if (!openRes.ok || openData == null) {
                postMain { bootError = openRes.errorMessage }
                return@runIo
            }
            val serverRes = parseEnvelope(Rust.nativeServerStatus())
            val safInfo = runCatching { SafAgentWorkspace.info(activity) }.getOrNull()
            val pendingRaw = updater.pending()
            val hasTok = openData.optBoolean("has_token", false)
            val initialChats = if (hasTok) parseChatSummaries(parseEnvelope(Rust.nativeListChats("chat"))) else emptyList()
            val firstConv = if (hasTok && initialChats.isNotEmpty()) {
                parseEnvelope(Rust.nativeGetChat(initialChats.first().id)).data as? JSONObject
            } else null

            postMain {
                applyStatus(openData)
                applyOnlineToPlatform(online)
                refreshPermissions()
                if (safInfo != null) {
                    agentRootConfigured = safInfo.configured
                    agentRootName = safInfo.name
                }
                applyServerEnvelope(serverRes)
                ensureLocalService()
                refreshTunnelStatus()
                updater.onLaunch()
                Updater.setPrefs(activity, online, updateRemind)

                if (pendingRaw.isNotEmpty()) {
                    updateManifest = parseManifest(pendingRaw)
                }

                screen = when {
                    !hasToken && !prefs.getBoolean("onboarded", false) -> AppScreen.ONBOARDING
                    !hasToken -> AppScreen.TOKEN
                    else -> {
                        chatList.clear()
                        chatList.addAll(initialChats)
                        if (firstConv != null) {
                            applyLoadedConversation(firstConv)
                        } else {
                            newDraftChat(false)
                        }
                        AppScreen.CHAT
                    }
                }

                if (hasToken && online) {
                    if (prefs.getBoolean("serverEnabled", false)) startLocalServer()
                    if (updateRemind && updateManifest != null) showUpdateDialog = true
                    updater.check(false)
                }
            }
        }
    }

    fun onResume() {
        foreground = true
        refreshPermissions()
        updater.onResume()
        ensureLocalService()
        refreshTunnelStatus()
        if (agentMode && currentChatId.isNotEmpty()) {
            refreshAgentFiles()
        }
    }

    fun onPause() { foreground = false }

    fun destroy() {
        destroyed.set(true)
        pollTask?.let { main.removeCallbacks(it) }
        pollTask = null
        main.removeCallbacksAndMessages(null)
        tunnel.setListener(null)
        sandbox.setListener(null)
        updater.setListener(null)
        browser.destroy()
        sandbox.stop()
        SandboxProxy.stop()
        KatexBitmapRenderer.destroy()
        io.shutdownNow()
    }

    fun handleBack(): Boolean {
        if (previewImageDataUrl != null) { previewImageDataUrl = null; return true }
        if (showUpdateDialog && !updateBusy) { showUpdateDialog = false; return true }
        if (showSandboxSheet) { showSandboxSheet = false; return true }
        if (showJournalSheet) { showJournalSheet = false; return true }
        if (showWorkspaceSheet) { showWorkspaceSheet = false; return true }
        if (showToolsSheet) { showToolsSheet = false; return true }
        if (showModelSheet) { showModelSheet = false; return true }
        if (screen == AppScreen.TERMINAL) { screen = AppScreen.CHAT; return true }
        if (screen == AppScreen.SETTINGS) {
            if (settingsPane != SettingsPane.ROOT) {
                settingsPane = SettingsPane.ROOT
            } else {
                screen = settingsReturn
            }
            return true
        }
        return false
    }

    // ── Permissions ──────────────────────────────────────────────────────
    fun refreshPermissions() {
        notifGranted = if (Build.VERSION.SDK_INT < 33) true else
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryIgnoring = pm != null && pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    fun requestBatteryExemption() {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        } catch (_: Exception) {
            try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    fun openAppSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        } catch (_: Exception) { showToast("Не удалось открыть настройки") }
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean("onboarded", true).apply()
        screen = if (hasToken) AppScreen.CHAT else AppScreen.TOKEN
    }

    // ── Auth / Token ─────────────────────────────────────────────────────
    fun selectAuthMode(mode: String) {
        authMode = if (mode == "web") "web" else "api"
        prefs.edit().putString("authMode", authMode).apply()
        tokenError = null
        tokenVerified = false
    }

    fun verifyAndSaveToken(token: String, stayInSettings: Boolean = false) {
        val clean = token.trim()
        if (clean.length < 8) return
        if (!online) { showToast("Нет доступа к интернету"); return }
        tokenChecking = true
        tokenError = null
        tokenVerified = false
        val targetMode = authMode
        runIo {
            val env = parseEnvelope(Rust.nativeSetToken(clean, targetMode))
            postMain {
                tokenChecking = false
                val data = env.data as? JSONObject
                if (!env.ok || data == null) {
                    tokenError = env.errorMessage
                    return@postMain
                }
                applyStatus(data)
                stopLocalService()
                refreshServerStatus()
                tokenVerified = true
                if (stayInSettings) showToast("Токен сохранён")
            }
        }
    }

    fun revalidateToken() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        tokenChecking = true
        runIo {
            val env = parseEnvelope(Rust.nativeRevalidate())
            postMain {
                tokenChecking = false
                val data = env.data as? JSONObject
                if (env.ok && data != null) {
                    applyStatus(data)
                    showToast("Ключ действителен")
                } else {
                    showToast(env.errorMessage)
                }
            }
        }
    }

    fun clearToken() {
        stopCfTunnel()
        stopLocalService()
        detachStream()
        runIo {
            val env = parseEnvelope(Rust.nativeClearToken())
            postMain {
                val data = env.data as? JSONObject
                if (!env.ok || data == null) { showToast(env.errorMessage); return@postMain }
                applyStatus(data)
                prefs.edit().putBoolean("serverEnabled", false).apply()
                serverInfo = ServerInfo()
                agentMode = false
                newDraftChat(false)
                tokenVerified = false
                tokenError = null
                screen = AppScreen.TOKEN
                showToast("Ключ удалён")
            }
        }
    }

    fun openChatAfterAuth() {
        refreshChats(openFirstIfAvailable = true)
        screen = AppScreen.CHAT
    }

    fun setOnlineEnabled(value: Boolean) {
        applyOnlineToPlatform(value)
        if (!value) {
            detachStream()
            stopCfTunnel()
            stopLocalService()
            prefs.edit().putBoolean("serverEnabled", false).apply()
            serverInfo = ServerInfo()
        }
        runIo {
            val env = parseEnvelope(Rust.nativeSetOnline(value))
            postMain {
                val data = env.data as? JSONObject
                if (!env.ok || data == null) { showToast(env.errorMessage); return@postMain }
                applyStatus(data)
                applyOnlineToPlatform(online)
                Updater.setPrefs(activity, online, updateRemind)
            }
        }
    }

    private fun applyOnlineToPlatform(enabled: Boolean) {
        SandboxProxy.setOnline(enabled)
        sandbox.setOnline(enabled)
        browser.setOnline(enabled)
        tunnel.setOnline(enabled)
    }

    // ── Appearance & AI Config ───────────────────────────────────────────
    fun updateThemeMode(m: AppThemeMode) { themeMode = m; prefs.edit().putString("theme", m.id).apply() }
    fun updatePalette(p: AppPalette) { palette = p; prefs.edit().putString("palette", p.id).apply() }
    fun updateUiScale(s: Int) { uiScale = s.coerceIn(55, 175); prefs.edit().putInt("uiScale", uiScale).apply() }
    fun updateTextZoom(z: Int) { textZoom = z.coerceIn(60, 200); prefs.edit().putInt("textZoom", textZoom).apply() }
    fun updateCompact(c: Boolean) { compact = c; prefs.edit().putBoolean("compact", c).apply() }
    fun updateModel(m: String) { model = m; prefs.edit().putString("model", m).apply() }
    fun updateThinking(t: Boolean) { thinking = t; prefs.edit().putBoolean("thinking", t).apply() }
    fun updateEffort(e: String) { effort = e; prefs.edit().putString("effort", e).apply() }
    fun updateSearch(s: Boolean) { search = s; prefs.edit().putBoolean("search", s).apply() }
    fun updateSystemPrompt(s: String) { systemPrompt = s.trim(); prefs.edit().putString("systemPrompt", systemPrompt).apply() }

    fun updateServerPort(port: Int) {
        val clamped = port.coerceIn(AppConfig.MIN_LOCAL_API_PORT, AppConfig.MAX_LOCAL_API_PORT)
        if (clamped == serverPort) return
        serverPort = clamped
        prefs.edit().putInt("serverPort", clamped).apply()
        if (serverInfo.running) {
            toggleServer(false)
            showToast("Порт $clamped сохранён — включите сервер заново")
        } else {
            showToast("Порт $clamped сохранён")
        }
    }

    fun isWebSession(): Boolean = if (hasToken) activeMode == "web" else authMode == "web"

    fun modelDisplayLabel(id: String): String = when (id) {
        AppConfig.DEFAULT_MODEL_ID -> "Flash 1017"
        AppConfig.PRO_MODEL_ID -> "V4 Pro"
        "deepseek-reasoner" -> "Reasoner"
        "deepseek-chat" -> "Chat"
        else -> id
    }

    fun modelShortLabel(): String = when {
        isWebSession() -> "DeepSeek"
        else -> modelDisplayLabel(model)
    }

    // ── Chats & Messages ─────────────────────────────────────────────────
    fun refreshChats(openFirstIfAvailable: Boolean = false) {
        val kind = if (agentMode) "agent" else "chat"
        val isAgentSnapshot = agentMode
        runIo {
            val items = parseChatSummaries(parseEnvelope(Rust.nativeListChats(kind)))
            postMain {
                if (agentMode != isAgentSnapshot) return@postMain
                chatList.clear()
                chatList.addAll(items)
                if (openFirstIfAvailable) {
                    if (items.isNotEmpty()) loadChat(items.first().id)
                    else newDraftChat(isAgentSnapshot)
                }
            }
        }
    }

    fun newDraftChat(isAgent: Boolean = agentMode) {
        detachStream()
        titleRequestedForChat = ""
        lastExecCommand = ""
        repeatExecCount = 0
        agentFiles.clear()
        messages.clear()
        currentChatId = ""
        currentChatTitle = if (isAgent) "Агент" else "Новый чат"
        if (isAgent) {
            runIo {
                val env = parseEnvelope(Rust.nativeNewChat("agent"))
                val c = env.data as? JSONObject
                postMain {
                    if (agentMode && env.ok && c != null) {
                        currentChatId = c.optString("id")
                        currentChatTitle = "Агент"
                        refreshAgentFiles()
                        refreshChats()
                    }
                }
            }
        }
    }

    fun setAgentModeEnabled(enabled: Boolean) {
        if (agentMode == enabled) return
        detachStream()
        agentMode = enabled
        addJournal("mode", if (enabled) "Включён режим агента" else "Выключен режим агента")
        refreshChats(openFirstIfAvailable = true)
        if (enabled) {
            refreshSandbox(openSheetIfNotInstalled = true)
        }
    }

    fun loadChat(id: String) {
        detachStream()
        val expectedAgent = agentMode
        runIo {
            val env = parseEnvelope(Rust.nativeGetChat(id))
            val conv = env.data as? JSONObject
            postMain {
                if (agentMode != expectedAgent) return@postMain
                if (!env.ok || conv == null) { showToast(env.errorMessage); return@postMain }
                applyLoadedConversation(conv)
                refreshAgentFiles()
                refreshChats()
            }
        }
    }

    private fun applyLoadedConversation(conv: JSONObject) {
        currentChatId = conv.optString("id")
        currentChatTitle = conv.optString("title").ifEmpty { if (agentMode) "Агент" else "Чат" }
        parseConversationMessages(conv)
    }

    fun deleteChat(id: String) {
        runIo {
            val env = parseEnvelope(Rust.nativeDeleteChat(id))
            postMain {
                if (!env.ok) { showToast(env.errorMessage); return@postMain }
                if (id == currentChatId) newDraftChat(agentMode)
                refreshChats()
                showToast("Чат удалён")
            }
        }
    }

    fun regenerateLast() {
        if (streaming || currentChatId.isEmpty()) return
        if (!hasToken) { screen = AppScreen.TOKEN; return }
        if (!online) { showToast("Нет доступа к интернету"); return }
        val chatId = currentChatId
        runIo {
            val env = parseEnvelope(Rust.nativePopLastTurn(chatId))
            val data = env.data as? JSONObject
            postMain {
                if (!env.ok || data == null) {
                    showToast(env.errorMessage)
                    return@postMain
                }
                val text = data.optString("text", "")
                val imgs = mutableListOf<String>()
                val arr = data.optJSONArray("images")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotEmpty() }?.let { imgs.add(it) }
                    }
                }
                data.optJSONObject("chat")?.let { applyLoadedConversation(it) }
                sendMessageWithImages(text, imgs)
            }
        }
    }

    private fun parseConversationMessages(conv: JSONObject) {
        messages.clear()
        val arr = conv.optJSONArray("messages") ?: return
        var toolNum = 0
        var i = 0
        while (i < arr.length()) {
            val m = arr.optJSONObject(i)
            if (m == null) { i++; continue }
            val role = m.optString("role", "user")
            val content = m.optString("content", "")
            val reasoning = m.optString("reasoning", "")
            if (role == "user" && content.startsWith("[TOOL_RESULT]")) {
                i++; continue
            }
            if (role == "user") toolNum = 0

            val imgs = mutableListOf<String>()
            val imgArr = m.optJSONArray("images")
            if (imgArr != null) {
                for (k in 0 until imgArr.length()) {
                    imgArr.optString(k).takeIf { it.isNotEmpty() }?.let { imgs.add(it) }
                }
            }
            val srcs = mutableListOf<WebSource>()
            val srcArr = m.optJSONArray("sources")
            if (srcArr != null) {
                for (k in 0 until srcArr.length()) {
                    val so = srcArr.optJSONObject(k) ?: continue
                    srcs.add(WebSource(so.optString("title"), so.optString("url"), so.optString("site"), so.optString("date")))
                }
            }

            val execCmd = if (agentMode && role == "assistant") AgentDirectives.extractExec(content) else ""
            val browserCmd = if (execCmd.isEmpty() && agentMode && role == "assistant") AgentDirectives.extractBrowser(content) else null
            val hasTool = execCmd.isNotEmpty() || browserCmd != null
            val visible = if (agentMode && role == "assistant") AgentDirectives.stripDirectives(content) else content
            if (role == "assistant" && !hasTool && visible.isEmpty() && reasoning.isEmpty()
                && AgentDirectives.extractMedia(content).isEmpty()
                && AgentDirectives.extractBrowse(content).isEmpty()
            ) {
                i++; continue
            }
            messages.add(
                ChatMessage(
                    role = role,
                    content = content,
                    reasoning = reasoning,
                    sources = srcs,
                    images = imgs,
                    ts = m.optLong("ts", System.currentTimeMillis()),
                    hasToolCall = hasTool
                )
            )
            if (hasTool) {
                toolNum++
                var resOut = "(прервано)"
                var resCode = "—"
                if (i + 1 < arr.length()) {
                    val next = arr.optJSONObject(i + 1)
                    if (next != null && next.optString("role") == "user"
                        && next.optString("content").startsWith("[TOOL_RESULT]")
                    ) {
                        val parsed = AgentDirectives.parseToolResult(next.optString("content"))
                        resOut = parsed.first
                        resCode = parsed.second
                        i++
                    }
                }
                val lbl = if (execCmd.isNotEmpty()) "Команда $toolNum" else "Браузер · ${browserCmd!!.first}"
                val cmdText = if (execCmd.isNotEmpty()) execCmd else browserCmd!!.second
                messages.add(
                    ChatMessage(
                        role = "tool",
                        content = "",
                        toolBlock = ToolBlockData(
                            id = "hist-$i",
                            label = lbl,
                            command = cmdText,
                            output = resOut,
                            exitCode = resCode,
                            running = false
                        )
                    )
                )
            }
            i++
        }
        loadSandboxNotices(currentChatId).forEach { notice ->
            messages.add(ChatMessage(role = "notice", content = notice.text, notice = notice))
        }
    }

    fun addImagesFromUris(uris: List<Uri>) {
        val slots = 4 - pendingAttachments.size
        if (slots <= 0) { showToast("Можно прикрепить не больше 4 фото"); return }
        val chosen = uris.take(slots)
        runIo {
            chosen.forEach { uri ->
                val encoded = encodeImageUri(uri)
                postMain {
                    if (encoded == null) showToast("Не удалось прочитать изображение")
                    else if (pendingAttachments.size < 4) pendingAttachments.add(encoded)
                }
            }
        }
    }

    fun sendMessage(rawText: String = composerDraft) {
        val images = pendingAttachments.map { it.dataUrl }
        val text = rawText.trim().ifEmpty { if (images.isNotEmpty()) "Опиши изображение." else "" }
        if (text.isEmpty() && images.isEmpty()) return
        composerDraft = ""
        pendingAttachments.clear()
        sendMessageWithImages(text, images)
    }

    private fun sendMessageWithImages(text: String, images: List<String>) {
        if (streaming) return
        if (!hasToken) { screen = AppScreen.TOKEN; return }
        if (!online) { showToast("Нет доступа к интернету"); return }

        agentToolCount = 0
        agentBrowserCount = 0
        agentToolBusy = false
        lastExecCommand = ""
        repeatExecCount = 0
        activeToolId = null

        messages.add(ChatMessage(role = "user", content = text, images = images))
        startSendInternal(text, images, isToolContinuation = false)
    }

    private fun continueAgentWithTool(output: String, exitCode: String, images: List<String>) {
        if (!agentMode || currentChatId.isEmpty() || streaming || !hasToken || !online) return
        val prompt = "[TOOL_RESULT]\nexit code: $exitCode\n$output\n\n" +
            "Продолжай. Если нужна ещё команда — ответь строкой [[EXEC: ...]]; для браузера — [[BROWSER: ...]]."
        startSendInternal(prompt, images, isToolContinuation = true)
    }

    private fun startSendInternal(text: String, images: List<String>, isToolContinuation: Boolean) {
        val chatIdSnapshot = currentChatId
        val chatTitleSnapshot = currentChatTitle
        val isAgentSnapshot = agentMode
        val sysPromptSnapshot = systemPrompt
        val sbSnapshot = sandboxState
        val selectedFilesSnapshot = agentFiles.filter { it.selected }.map { it.name }
        val sendModel = if (images.isNotEmpty() && !isWebSession()) AppConfig.DEFAULT_MODEL_ID else model
        val effortSnapshot = effort
        val thinkingSnapshot = thinking
        val searchSnapshot = search

        streaming = true
        streamStage = if (searchSnapshot) "searching" else if (thinkingSnapshot) "reasoning" else "writing"
        streamReasoning = ""
        streamContent = ""
        streamStartedMs = SystemClock.elapsedRealtime()
        firstTokenMs = 0
        reasoningStartMs = 0
        reasoningEndMs = 0

        runIo {
            val fileContext = if (isAgentSnapshot && chatIdSnapshot.isNotEmpty()) {
                SafAgentWorkspace.syncFromSaf(activity, chatIdSnapshot, chatTitleSnapshot)
                readAgentFileContextSync(chatIdSnapshot, selectedFilesSnapshot)
            } else {
                emptyMap()
            }
            val fullSystem = AgentPromptBuilder.buildFullSystemPrompt(
                userSystemPrompt = sysPromptSnapshot,
                agentMode = isAgentSnapshot,
                sandboxState = sbSnapshot,
                selectedFileNames = selectedFilesSnapshot,
                fileContext = fileContext
            )
            val res = parseEnvelope(
                Rust.nativeSend(
                    chatIdSnapshot,
                    text,
                    sendModel,
                    effortSnapshot,
                    thinkingSnapshot,
                    searchSnapshot,
                    JSONArray(images).toString(),
                    fullSystem
                )
            )
            postMain {
                if (!streaming) return@postMain
                val start = res.data as? JSONObject
                if (!res.ok || start == null) {
                    streaming = false
                    streamStage = null
                    if (!isToolContinuation && messages.lastOrNull()?.role == "user") {
                        messages.removeAt(messages.lastIndex)
                        composerDraft = text
                    }
                    agentToolBusy = false
                    showToast(res.errorMessage)
                    return@postMain
                }
                val conv = start.getJSONObject("chat")
                currentChatId = conv.optString("id")
                currentChatTitle = conv.optString("title").ifEmpty { if (agentMode) "Агент" else "Чат" }
                activeStreamId = start.optLong("stream_id")
                refreshChats()
                startPolling(activeStreamId)
            }
        }
    }

    private fun startPolling(streamId: Long) {
        pollTask?.let { main.removeCallbacks(it) }
        pollInFlight.set(false)
        val r = object : Runnable {
            override fun run() {
                if (activeStreamId != streamId || !streaming) return
                if (!pollInFlight.compareAndSet(false, true)) {
                    main.postDelayed(this, 60)
                    return
                }
                val self = this
                runIo {
                    val env = parseEnvelope(Rust.nativePoll(streamId))
                    pollInFlight.set(false)
                    postMain {
                        if (activeStreamId != streamId || !streaming) return@postMain
                        val p = env.data as? JSONObject
                        if (!env.ok || p == null) {
                            finishStream(JSONObject().put("error", env.errorMessage).put("done", true))
                            return@postMain
                        }
                        val rText = p.optString("reasoning", "")
                        val cText = p.optString("content", "")
                        val rawStage = if (p.isNull("stage")) null else p.optString("stage").takeIf { it.isNotEmpty() }
                        if (rText.isNotEmpty() || cText.isNotEmpty()) {
                            val now = SystemClock.elapsedRealtime()
                            if (firstTokenMs == 0L) firstTokenMs = now
                            if (rText.isNotEmpty() && reasoningStartMs == 0L) reasoningStartMs = now
                            if (cText.isNotEmpty() && reasoningStartMs != 0L && reasoningEndMs == 0L) reasoningEndMs = now
                        }
                        streamStage = when {
                            cText.isNotEmpty() -> "writing"
                            rText.isNotEmpty() -> "reasoning"
                            rawStage == "uploading" -> "uploading"
                            search && rawStage == "searching" -> "searching"
                            thinking -> "reasoning"
                            else -> "writing"
                        }
                        streamReasoning = rText
                        streamContent = cText
                        if (p.optBoolean("done", false)) {
                            finishStream(p)
                        } else {
                            main.postDelayed(self, 60)
                        }
                    }
                }
            }
        }
        pollTask = r
        main.post(r)
    }

    private fun finishStream(p: JSONObject) {
        pollTask?.let { main.removeCallbacks(it) }
        pollTask = null
        pollInFlight.set(false)
        val finishedAt = SystemClock.elapsedRealtime()
        val elapsedMs = if (streamStartedMs > 0) finishedAt - streamStartedMs else 0L
        val genMs = if (firstTokenMs > 0) finishedAt - firstTokenMs else elapsedMs
        val reasoningMs = if (reasoningStartMs > 0) (if (reasoningEndMs > 0) reasoningEndMs else finishedAt) - reasoningStartMs else 0L

        val content = p.optString("content", streamContent)
        val reasoning = p.optString("reasoning", streamReasoning)
        val cancelled = p.optBoolean("cancelled", false)
        val error = if (p.isNull("error")) null else p.optString("error").takeIf { it.isNotEmpty() }
        val uo = p.optJSONObject("usage")
        val usage = uo?.let {
            UsageStats(it.optLong("prompt_tokens"), it.optLong("completion_tokens"), it.optLong("total_tokens"))
        }
        val estCompletion = usage?.completionTokens?.takeIf { it > 0 } ?: AgentDirectives.estimateTokens(content + reasoning).toLong()
        val tps = if (estCompletion > 0 && genMs > 0) estCompletion / (genMs / 1000.0) else 0.0

        val srcs = mutableListOf<WebSource>()
        val sArr = p.optJSONArray("sources")
        if (sArr != null) {
            for (k in 0 until sArr.length()) {
                val so = sArr.optJSONObject(k) ?: continue
                srcs.add(WebSource(so.optString("title"), so.optString("url"), so.optString("site"), so.optString("date")))
            }
        }

        val hasTool = agentMode && (AgentDirectives.extractExec(content).isNotEmpty() || AgentDirectives.extractBrowser(content) != null)
        messages.add(
            ChatMessage(
                role = "assistant",
                content = content,
                reasoning = reasoning,
                sources = srcs,
                usage = usage,
                elapsedMs = elapsedMs,
                reasoningMs = reasoningMs,
                tokPerSec = tps,
                cancelled = cancelled,
                error = error ?: if (content.isEmpty() && !cancelled && !hasTool) "Модель вернула пустой ответ." else null,
                hasToolCall = hasTool
            )
        )

        streaming = false
        activeStreamId = 0
        streamStage = null
        streamReasoning = ""
        streamContent = ""

        if (!foreground && !cancelled && error == null && !hasTool) {
            postFinishedNotification(currentChatTitle)
        }

        requestTitleIfNeeded()
        refreshChats()
        maybeRunAgentTool(content)
    }

    fun cancelStream() {
        val id = activeStreamId
        if (id > 0) runIo { Rust.nativeCancel(id) }
    }

    private fun detachStream() {
        pollTask?.let { main.removeCallbacks(it) }
        pollTask = null
        pollInFlight.set(false)
        val id = activeStreamId
        if (id > 0) runIo { Rust.nativeCancel(id) }
        activeStreamId = 0
        streaming = false
        streamStage = null
        streamReasoning = ""
        streamContent = ""
        agentToolBusy = false
        activeToolId = null
        activeToolBlockIndex = -1
    }

    private fun requestTitleIfNeeded() {
        if (currentChatId.isEmpty() || titleRequestedForChat == currentChatId) return
        val users = messages.filter { it.role == "user" }
        if (users.size != 1) return
        val chatId = currentChatId
        titleRequestedForChat = chatId
        val prompt = users.first().content.ifEmpty { "Опиши изображение." }
        runIo {
            val env = parseEnvelope(Rust.nativeGenerateTitle(chatId, prompt))
            if (env.ok) {
                val title = (env.data as? JSONObject)?.optString("title").orEmpty()
                if (title.isNotEmpty()) {
                    postMain {
                        if (currentChatId == chatId) currentChatTitle = title
                        refreshChats()
                    }
                }
            }
        }
    }

    private fun maybeRunAgentTool(content: String) {
        if (!agentMode || content.isEmpty()) return
        val browserCmd = AgentDirectives.extractBrowser(content)
        if (browserCmd != null) {
            if (agentBrowserCount >= 24) {
                messages.add(ChatMessage(role = "notice", content = "Лимит действий браузера (24)", notice = SandboxNotice("Лимит браузера (24)", "error")))
                return
            }
            if (agentToolBusy) return
            val chatId = currentChatId
            agentBrowserCount++
            agentToolBusy = true
            val idx = messages.size
            val toolId = "browser-${System.currentTimeMillis()}"
            messages.add(
                ChatMessage(
                    role = "tool",
                    content = "",
                    toolBlock = ToolBlockData(toolId, "Браузер · ${browserCmd.first}", browserCmd.second, running = true)
                )
            )
            addJournal("browser", "${browserCmd.first}: ${browserCmd.second}")
            browser.command(browserCmd.first, browserCmd.second) { res ->
                postMain {
                    agentToolBusy = false
                    if (idx in messages.indices) {
                        val tb = messages[idx].toolBlock
                        val ok = res?.optBoolean("ok", false) == true
                        val text = formatBrowserResult(res, ok)
                        if (tb != null) {
                            messages[idx] = messages[idx].copy(
                                toolBlock = tb.copy(output = text, exitCode = if (ok) "0" else "1", running = false)
                            )
                        }
                        if (agentMode && currentChatId == chatId) {
                            val shot = res?.optString("screenshot").orEmpty().takeIf { it.isNotEmpty() }
                            continueAgentWithTool(text, if (ok) "0" else "1", listOfNotNull(shot))
                        }
                    }
                }
            }
            return
        }

        val cmd = AgentDirectives.extractExec(content)
        if (cmd.isEmpty()) return
        if (!sandboxState.running) {
            messages.add(ChatMessage(role = "notice", content = "Песочница не запущена. Откройте Ubuntu и запустите её.", notice = SandboxNotice("Песочница не запущена", "error")))
            return
        }
        if (cmd == lastExecCommand) {
            repeatExecCount++
            if (repeatExecCount >= 2) {
                messages.add(ChatMessage(role = "notice", content = "Цикл остановлен: повтор одной и той же команды", notice = SandboxNotice("Повтор команды остановлен", "error")))
                return
            }
        } else {
            lastExecCommand = cmd
            repeatExecCount = 0
        }
        if (agentToolCount >= 8) {
            messages.add(ChatMessage(role = "notice", content = "Достигнут лимит команд за один ответ (8)", notice = SandboxNotice("Лимит команд (8)", "error")))
            return
        }
        if (agentToolBusy) return
        agentToolCount++
        agentToolBusy = true
        val id = "tool-${System.currentTimeMillis()}-$agentToolCount"
        activeToolId = id
        activeToolChatId = currentChatId
        activeToolBlockIndex = messages.size
        messages.add(
            ChatMessage(
                role = "tool",
                content = "",
                toolBlock = ToolBlockData(id, "Команда $agentToolCount", cmd, running = true)
            )
        )
        addJournal("agent", "exec: $cmd")
        sandbox.exec(id, cmd)
    }

    private fun formatBrowserResult(res: JSONObject?, ok: Boolean): String {
        if (!ok || res == null) {
            return res?.optString("error", "Ошибка браузера") ?: "Ошибка браузера"
        }
        return buildString {
            append("URL: ").append(res.optString("url"))
            append("\nTitle: ").append(res.optString("title"))
            val scrollH = res.optInt("scrollH", 0)
            if (scrollH > 0) {
                append("\nScroll: ").append(res.optInt("scrollY", 0))
                    .append(" / ").append(scrollH)
                    .append(" (viewport ").append(res.optInt("viewH", 0)).append(")")
            }
            val bodyTxt = res.optString("text")
            if (bodyTxt.isNotEmpty()) append("\n\n").append(bodyTxt)
            val els = res.optJSONArray("elements")
            if (els != null && els.length() > 0) {
                append("\n\nИнтерактивные элементы (можно передавать номер, текст или CSS):\n")
                for (i in 0 until els.length()) {
                    val el = els.optJSONObject(i) ?: continue
                    val ref = el.optInt("ref")
                    val tag = el.optString("tag")
                    val tp = el.optString("type")
                    append("[").append(ref).append("] <").append(tag)
                    if (tp.isNotEmpty()) append(" type=\"").append(tp).append("\"")
                    if (el.has("checked")) append(if (el.optBoolean("checked")) " checked" else " unchecked")
                    append(">")
                    val txt = el.optString("text")
                    val ph = el.optString("placeholder")
                    val nm = el.optString("name")
                    val valStr = el.optString("value")
                    val href = el.optString("href")
                    if (txt.isNotEmpty()) append(" \"").append(txt).append("\"")
                    if (ph.isNotEmpty()) append(" placeholder=\"").append(ph).append("\"")
                    if (nm.isNotEmpty()) append(" name=").append(nm)
                    if (valStr.isNotEmpty()) append(" value=\"").append(valStr).append("\"")
                    val opts = el.optJSONArray("options")
                    if (opts != null && opts.length() > 0) {
                        append(" options=[")
                        for (k in 0 until opts.length()) {
                            if (k > 0) append(", ")
                            append(opts.optString(k))
                        }
                        append("]")
                    }
                    if (href.isNotEmpty()) append(" -> ").append(href)
                    append("\n")
                }
            }
        }
    }

    // ── Async / Cached Image Decoding (never blocks UI thread) ───────────
    fun getCachedImageBitmap(dataUrl: String): ImageBitmap? = synchronized(cacheLock) {
        dataUrlImageCache[dataUrl]
    }

    fun decodeImageBitmapAsync(dataUrl: String, onReady: (ImageBitmap?) -> Unit) {
        getCachedImageBitmap(dataUrl)?.let {
            onReady(it)
            return
        }
        runIo {
            val bmp = runCatching {
                val b = Base64.decode(dataUrl.substringAfter(",", dataUrl), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
            if (bmp != null) {
                synchronized(cacheLock) { dataUrlImageCache[dataUrl] = bmp }
            }
            postMain { onReady(bmp) }
        }
    }

    fun getCachedAgentMedia(content: String): List<Pair<String, ImageBitmap>>? {
        if (!agentMode || currentChatId.isEmpty()) return emptyList()
        val names = AgentDirectives.extractMedia(content)
        if (names.isEmpty()) return emptyList()
        val key = "$currentChatId:${names.joinToString("|")}"
        return synchronized(cacheLock) { agentMediaCache[key] }
    }

    fun loadAgentMediaBitmapsAsync(content: String, onReady: (List<Pair<String, ImageBitmap>>) -> Unit) {
        if (!agentMode || currentChatId.isEmpty()) {
            onReady(emptyList())
            return
        }
        val chatId = currentChatId
        val names = AgentDirectives.extractMedia(content)
        if (names.isEmpty()) {
            onReady(emptyList())
            return
        }
        val key = "$chatId:${names.joinToString("|")}"
        synchronized(cacheLock) { agentMediaCache[key] }?.let {
            onReady(it)
            return
        }
        runIo {
            val out = mutableListOf<Pair<String, ImageBitmap>>()
            names.forEach { name ->
                try {
                    val media = AgentWorkspace.readMediaBytes(activity, chatId, name, 12 * 1024 * 1024)
                    if (media.mime.lowercase(Locale.US).startsWith("image/")) {
                        val bmp = BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size)?.asImageBitmap()
                        if (bmp != null) out.add(name to bmp)
                    }
                } catch (_: Exception) { }
            }
            synchronized(cacheLock) { agentMediaCache[key] = out }
            postMain { onReady(out) }
        }
    }

    // ── Workspace & Sandbox & Tunnel Actions ─────────────────────────────
    fun refreshAgentRoot() {
        runIo {
            val info = runCatching { SafAgentWorkspace.info(activity) }.getOrNull()
            postMain {
                agentRootConfigured = info?.configured == true
                agentRootName = info?.name.orEmpty()
            }
        }
    }

    fun useInternalWorkspace() {
        SafAgentWorkspace.clearRoot(activity)
        agentRootConfigured = false
        agentRootName = ""
        refreshAgentFiles()
        showToast("Используем папку приложения")
    }

    fun setSafRoot(uri: Uri) {
        val chatId = currentChatId
        val title = currentChatTitle
        runIo {
            try {
                SafAgentWorkspace.setRoot(activity, uri)
                if (chatId.isNotEmpty()) {
                    SafAgentWorkspace.syncToSaf(activity, chatId, title)
                    SafAgentWorkspace.syncFromSaf(activity, chatId, title)
                }
                val info = SafAgentWorkspace.info(activity)
                postMain {
                    agentRootConfigured = info.configured
                    agentRootName = info.name
                    refreshAgentFiles()
                    showToast("Папка workspace синхронизирована")
                }
            } catch (e: Exception) {
                postMain { showToast(e.message ?: "Не удалось сохранить папку") }
            }
        }
    }

    fun openAgentFolder() {
        if (!agentRootConfigured) { hostActions?.chooseAgentRoot(); return }
        if (currentChatId.isEmpty()) newDraftChat(true)
        val chatId = currentChatId
        val title = currentChatTitle
        if (chatId.isEmpty()) return
        runIo {
            try {
                SafAgentWorkspace.syncToSaf(activity, chatId, title)
                val dir = SafAgentWorkspace.chatDir(activity, chatId, title, true)
                postMain {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(dir.uri, "vnd.android.document/directory")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        showToast("Файловый менеджер не открыл папку")
                    }
                }
            } catch (_: Exception) {
                postMain { showToast("Файловый менеджер не открыл папку") }
            }
        }
    }

    fun importAgentZip(uri: Uri) {
        if (currentChatId.isEmpty()) {
            runIo {
                val env = parseEnvelope(Rust.nativeNewChat("agent"))
                val c = env.data as? JSONObject
                val newId = c?.optString("id").orEmpty()
                if (newId.isNotEmpty()) {
                    postMain {
                        currentChatId = newId
                        currentChatTitle = "Агент"
                        refreshChats()
                    }
                    importZipIntoChat(newId, "Агент", uri)
                }
            }
            return
        }
        importZipIntoChat(currentChatId, currentChatTitle, uri)
    }

    private fun importZipIntoChat(chatId: String, title: String, uri: Uri) {
        runIo {
            try {
                val name = AgentWorkspace.copyZip(activity, chatId, uri)
                SafAgentWorkspace.syncToSaf(activity, chatId, title)
                postMain {
                    addJournal("zip", "ZIP добавлен: $name")
                    if (currentChatId == chatId) refreshAgentFiles()
                    showToast("ZIP добавлен в /workspace: $name")
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Ошибка добавления ZIP"
                postMain {
                    addJournal("error", msg)
                    showToast(msg)
                }
            }
        }
    }

    fun refreshAgentFiles() {
        if (!agentMode || currentChatId.isEmpty()) {
            agentFiles.clear()
            return
        }
        val chatId = currentChatId
        val title = currentChatTitle
        runIo {
            SafAgentWorkspace.syncFromSaf(activity, chatId, title)
            val entries = runCatching { AgentWorkspace.listEntries(activity, chatId) }.getOrDefault(emptyList())
            val selJson = prefs.getString("agentSel:$chatId", "{}") ?: "{}"
            val selObj = runCatching { JSONObject(selJson) }.getOrDefault(JSONObject())
            val mapped = entries.map { entry ->
                val selected = if (selObj.has(entry.name)) selObj.optBoolean(entry.name, true) else true
                AgentFileItem(entry.name, entry.size, selected)
            }
            postMain {
                if (agentMode && currentChatId == chatId) {
                    agentFiles.clear()
                    agentFiles.addAll(mapped)
                }
            }
        }
    }

    fun toggleAgentFile(name: String) {
        val idx = agentFiles.indexOfFirst { it.name == name }
        if (idx < 0) return
        val next = !agentFiles[idx].selected
        agentFiles[idx] = agentFiles[idx].copy(selected = next)
        val o = JSONObject()
        agentFiles.forEach { o.put(it.name, it.selected) }
        prefs.edit().putString("agentSel:$currentChatId", o.toString()).apply()
    }

    fun deleteAgentFile(name: String) {
        if (currentChatId.isEmpty()) return
        val chatId = currentChatId
        val title = currentChatTitle
        runIo {
            val localOk = AgentWorkspace.delete(activity, chatId, name)
            val safOk = SafAgentWorkspace.delete(activity, chatId, title, name)
            if (localOk || safOk) {
                postMain { refreshAgentFiles() }
            }
        }
    }

    fun refreshSandbox(openSheetIfNotInstalled: Boolean = false) {
        runIo {
            val st = runCatching { sandbox.snapshot() }.getOrNull() ?: return@runIo
            postMain {
                sandboxState = st.toUiModel(sandboxState)
                if (openSheetIfNotInstalled && !st.installed) {
                    showSandboxSheet = true
                }
            }
        }
    }

    fun installSandbox() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        addJournal("sandbox", "Начата загрузка Ubuntu")
        sandboxState = sandboxState.copy(installing = true, progressPercent = 0)
        sandbox.install()
    }

    fun startSandbox() {
        val startForChat: (String, String) -> Unit = { chatId, title ->
            addJournal("sandbox", "Запуск QEMU")
            runIo {
                SafAgentWorkspace.syncFromSaf(activity, chatId, title)
                sandbox.start(chatId)
            }
        }
        if (currentChatId.isEmpty()) {
            runIo {
                val env = parseEnvelope(Rust.nativeNewChat("agent"))
                val newId = (env.data as? JSONObject)?.optString("id").orEmpty()
                if (newId.isNotEmpty()) {
                    postMain {
                        currentChatId = newId
                        currentChatTitle = "Агент"
                        refreshChats()
                        startForChat(newId, "Агент")
                    }
                }
            }
        } else {
            startForChat(currentChatId, currentChatTitle)
        }
    }

    fun stopSandbox() { sandbox.stop() }

    fun installSandboxBase() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        if (!sandboxState.running) {
            showToast("Сначала запустите песочницу")
            return
        }
        addJournal("sandbox", "Установка базовых пакетов (install-base)")
        sandbox.installBasePackages()
        refreshSandbox()
        showToast("Установка пакетов запущена (смотрите лог)")
    }

    fun testSandboxNetwork() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        addJournal("sandbox", "Проверка сети песочницы")
        sandbox.testNetwork()
        refreshSandbox()
    }

    fun cancelAgentExec() {
        if (!agentToolBusy) return
        sandbox.interruptExec()
        val idx = activeToolBlockIndex
        if (idx in messages.indices) {
            val msg = messages[idx]
            val tb = msg.toolBlock
            if (tb != null && tb.running) {
                val prev = tb.output.trim()
                val stoppedOut = if (prev.isEmpty()) "[Остановлено пользователем]" else "$prev\n[Остановлено пользователем]"
                messages[idx] = msg.copy(toolBlock = tb.copy(output = stoppedOut, exitCode = "130", running = false))
            }
        }
        activeToolId = null
        activeToolChatId = null
        activeToolBlockIndex = -1
        agentToolBusy = false
        addJournal("agent", "Команда остановлена пользователем (Ctrl+C)")
        showToast("Команда остановлена")
    }

    fun updateSandboxResources(ramMb: Int, cores: Int) {
        try {
            val st = sandbox.setConfig(ramMb, cores)
            sandboxState = st.toUiModel(sandboxState)
            showToast(if (sandboxState.running) "Сохранено; перезапустите песочницу" else "Параметры песочницы сохранены")
        } catch (e: Exception) { showToast(e.message ?: "Ошибка") }
    }

    fun openTerminal() {
        runIo {
            val st = runCatching { sandbox.snapshot() }.getOrNull() ?: return@runIo
            postMain {
                sandboxState = st.toUiModel(sandboxState)
                if (!st.installed) {
                    showSandboxSheet = true
                    showToast("Сначала установите песочницу")
                    return@postMain
                }
                if (!st.running) startSandbox()
                if (terminalOutput.isEmpty() && sandboxState.log.isNotEmpty()) terminalOutput = sandboxState.log
                screen = AppScreen.TERMINAL
            }
        }
    }

    fun writeTerminal(cmd: String) {
        runIo { sandbox.write(cmd + "\n") }
    }

    // ── Server & Tunnel ──────────────────────────────────────────────────
    fun refreshServerStatus() {
        if (!hasToken || !online) { serverInfo = ServerInfo(); return }
        runIo {
            val env = parseEnvelope(Rust.nativeServerStatus())
            postMain { applyServerEnvelope(env) }
        }
    }

    private fun applyServerEnvelope(env: Envelope) {
        val o = env.data as? JSONObject
        serverInfo = if (o != null) {
            ServerInfo(o.optBoolean("running"), o.optInt("port"), o.optString("base_url"), o.optString("key"))
        } else {
            ServerInfo()
        }
    }

    fun toggleServer(on: Boolean) {
        if (on) {
            startLocalServer()
        } else {
            stopCfTunnel()
            prefs.edit().putBoolean("serverEnabled", false).apply()
            stopLocalService()
            serverInfo = ServerInfo()
            runIo { Rust.nativeStopServer() }
        }
    }

    private fun startLocalServer() {
        val port = serverPort
        runIo {
            val env = parseEnvelope(Rust.nativeStartServer(port))
            postMain {
                val o = env.data as? JSONObject
                if (!env.ok || o == null) { showToast(env.errorMessage); return@postMain }
                serverInfo = ServerInfo(o.optBoolean("running"), o.optInt("port"), o.optString("base_url"), o.optString("key"))
                prefs.edit().putBoolean("serverEnabled", true).apply()
                ensureLocalService()
            }
        }
    }

    private fun ensureLocalService() {
        if (!serverInfo.running || serverInfo.port <= 0) return
        try {
            val intent = Intent(activity, ServerService::class.java).putExtra("port", serverInfo.port)
            activity.startForegroundService(intent)
        } catch (_: Exception) { }
    }

    private fun stopLocalService() {
        try { activity.stopService(Intent(activity, ServerService::class.java)) } catch (_: Exception) { }
    }

    fun refreshTunnelStatus() {
        tunnelInfo = tunnel.status().toUiModel()
    }

    fun selectCfMode(m: String) {
        if (tunnelInfo.state == "starting" || tunnelInfo.state == "connected") stopCfTunnel()
        cfMode = if (m == "token") "token" else "quick"
        prefs.edit().putString("cfMode", cfMode).apply()
    }

    fun startCfTunnel() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        if (!serverInfo.running) { showToast("Сначала включите локальный API"); return }
        if (cfMode == "token" && (cfToken.trim().isEmpty() || cfHost.trim().isEmpty())) {
            showToast("Укажите токен и публичный адрес")
            return
        }
        prefs.edit().putString("cfHost", cfHost.trim()).apply()
        ensureLocalService()
        tunnelInfo = tunnel.start(cfMode, cfToken.trim(), cfHost.trim(), serverInfo.port).toUiModel()
    }

    fun stopCfTunnel() {
        tunnel.stop()
        refreshTunnelStatus()
    }

    // ── Updates ──────────────────────────────────────────────────────────
    fun setUpdateRemindEnabled(on: Boolean) {
        updateRemind = on
        prefs.edit().putBoolean("updateRemind", on).apply()
        Updater.setPrefs(activity, online, on)
    }

    fun checkUpdateManual() {
        if (!online) { showToast("Нет доступа к интернету"); return }
        updateStatusText = "Проверяю…"
        updater.check(true)
    }

    fun downloadUpdate() {
        updateBusy = true
        updateError = null
        updateProgress = 0
        updater.download()
    }

    // ── Utilities ────────────────────────────────────────────────────────
    fun copyText(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Local API DeepSeek", text))
        showToast("Скопировано")
    }

    fun readClipboard(): String {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(activity)?.toString().orEmpty()
    }

    fun openUrl(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme == "http" || uri.scheme == "https") {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (_: Exception) { showToast("Не удалось открыть ссылку") }
    }

    fun showToast(msg: String) {
        toastMessage = msg
        main.removeCallbacks(clearToastTask)
        main.postDelayed(clearToastTask, 2200)
    }

    private val clearToastTask = Runnable { toastMessage = null }

    private var cachedSysPromptKey: String = ""
    private var cachedSysPromptTokens: Int = 0

    fun estimateContextTokens(): Int {
        if (systemPrompt != cachedSysPromptKey) {
            cachedSysPromptKey = systemPrompt
            cachedSysPromptTokens = AgentDirectives.estimateTokens(systemPrompt)
        }
        var total = cachedSysPromptTokens
        messages.forEach { m -> total += m.estTokens }
        if (agentMode) {
            total += if (sandboxState.running) 220 else 110
            agentFiles.forEach { f ->
                if (f.selected) total += ((f.size + 2) / 3).toInt()
            }
        }
        if (streaming) {
            total += AgentDirectives.estimateTokens(streamContent) + AgentDirectives.estimateTokens(streamReasoning)
        }
        total += AgentDirectives.estimateTokens(composerDraft)
        return total
    }

    private fun readAgentFileContextSync(
        chatId: String,
        selectedNames: List<String>
    ): Map<String, Pair<String, Boolean>> {
        val out = LinkedHashMap<String, Pair<String, Boolean>>()
        val budget = 250_000
        var used = 0
        for (name in selectedNames) {
            if (used >= budget) break
            try {
                val data = AgentWorkspace.readText(activity, chatId, name, 128 * 1024)
                if (!data.binary) {
                    var text = data.text
                    var trunc = data.truncated
                    val left = budget - used
                    if (text.length > left) {
                        text = text.take(left)
                        trunc = true
                    }
                    if (text.isNotEmpty()) {
                        out[name] = text to trunc
                        used += text.length
                    }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun applyStatus(o: JSONObject) {
        hasToken = o.optBoolean("has_token", false)
        online = o.optBoolean("online", true)
        activeMode = o.optString("mode", "api")
        val modelsArr = o.optJSONArray("models")
        if (modelsArr != null && modelsArr.length() > 0) {
            val discovered = (0 until modelsArr.length())
                .mapNotNull { modelsArr.optString(it).takeIf { s -> s.isNotBlank() } }
                .distinct()
            if (discovered.isNotEmpty()) {
                availableModels = discovered
            }
        }
        val b = o.optJSONObject("balance")
        balance = b?.let {
            BalanceInfo(
                it.optBoolean("available", true),
                it.optString("currency", "USD"),
                it.optString("total", "0"),
                it.optString("granted", "0"),
                it.optString("topped_up", "0")
            )
        }
    }

    private fun SandboxManager.Status.toUiModel(prev: SandboxState): SandboxState = prev.copy(
        installed = installed,
        installing = installing,
        running = running,
        shellReady = shellReady,
        baseInstalled = baseInstalled,
        installingBase = installingBase,
        installedTools = installedTools,
        networkStatus = networkStatus,
        version = version,
        ramMb = ramMb,
        cores = cores,
        maxRamMb = maxRamMb,
        maxCores = maxCores,
        log = log.ifEmpty { prev.log }
    )

    private fun CloudflareTunnel.Status.toUiModel(): TunnelInfo = TunnelInfo(
        state = state,
        mode = mode.ifEmpty { cfMode },
        url = url,
        error = error,
        detail = detail,
        port = port
    )

    private fun parseChatSummaries(env: Envelope): List<ChatSummaryItem> {
        val arr = env.data as? JSONArray ?: return emptyList()
        val list = ArrayList<ChatSummaryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                ChatSummaryItem(
                    id = o.optString("id"),
                    title = o.optString("title", "Без названия"),
                    updatedAt = o.optLong("updated_at", 0L),
                    messageCount = o.optInt("message_count", 0)
                )
            )
        }
        return list
    }

    private fun addJournal(kind: String, msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        agentJournal.add(0, "$stamp [$kind] $msg")
        while (agentJournal.size > 200) agentJournal.removeAt(agentJournal.lastIndex)
        prefs.edit().putString("agentJournal", JSONArray(agentJournal).toString()).apply()
    }

    fun clearJournal() {
        agentJournal.clear()
        prefs.edit().remove("agentJournal").apply()
    }

    private fun loadJournal() {
        try {
            val arr = JSONArray(prefs.getString("agentJournal", "[]") ?: "[]")
            for (i in 0 until arr.length()) agentJournal.add(arr.optString(i))
        } catch (_: Exception) { }
    }

    private fun appendNotice(chatId: String, text: String, kind: String) {
        val targetId = chatId.ifEmpty { currentChatId }
        if (targetId.isEmpty()) return
        val n = SandboxNotice(text, kind)
        val list = loadSandboxNotices(targetId).toMutableList()
        list.add(n)
        val arr = JSONArray()
        list.takeLast(30).forEach {
            arr.put(JSONObject().put("text", it.text).put("kind", it.kind).put("ts", it.ts))
        }
        prefs.edit().putString("sbNotices:$targetId", arr.toString()).apply()
        if (agentMode && currentChatId == targetId) {
            messages.add(ChatMessage(role = "notice", content = text, notice = n))
        }
    }

    private fun loadSandboxNotices(chatId: String): List<SandboxNotice> {
        if (chatId.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(prefs.getString("sbNotices:$chatId", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SandboxNotice(it.optString("text"), it.optString("kind"), it.optLong("ts")) }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun encodeImageUri(uri: Uri): PendingAttachment? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            var ori = ExifInterface.ORIENTATION_NORMAL
            runCatching {
                activity.contentResolver.openInputStream(uri)?.use {
                    ori = ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }
            val m = Matrix()
            when (ori) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            if (!m.isIdentity) {
                val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rot != bmp) { bmp.recycle(); bmp = rot }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            PendingAttachment(dataUrl, bmp)
        }
    } catch (_: Exception) { null }

    private fun postFinishedNotification(title: String) {
        if (Build.VERSION.SDK_INT >= 33 && !notifGranted) return
        try {
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.createNotificationChannel(NotificationChannel("replies", "Ответы", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getActivity(activity, 0, open, flags)
            val notif = Notification.Builder(activity, "replies")
                .setSmallIcon(R.drawable.ic_stat_reply)
                .setContentTitle("Ответ готов")
                .setContentText(title)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(41, notif)
        } catch (_: Exception) { }
    }

    private data class Envelope(val ok: Boolean, val data: Any? = null, val errorMessage: String = "")

    private fun parseEnvelope(raw: String): Envelope = try {
        val o = JSONObject(raw)
        if (o.optBoolean("ok", false)) {
            Envelope(true, o.opt("data"))
        } else {
            val err = o.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "Неизвестная ошибка" }
            Envelope(false, errorMessage = err)
        }
    } catch (_: Exception) {
        Envelope(false, errorMessage = "Некорректный ответ движка")
    }

    private fun parseManifest(raw: String?): UpdateManifest? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            val url = o.optString("url")
            if (url.isEmpty()) null else UpdateManifest(
                o.optInt("versionCode"), o.optString("versionName"), url, o.optString("sha256"), o.optString("notes")
            )
        } catch (_: Exception) { null }
    }

    companion object {
        fun stripDirectives(text: String): String = AgentDirectives.stripDirectives(text)
        fun extractBrowse(text: String): String = AgentDirectives.extractBrowse(text)
        fun browserBrowseUrl(query: String): String = AgentDirectives.browserBrowseUrl(query)
        fun estimateTokens(text: String): Int = AgentDirectives.estimateTokens(text)
        fun formatDuration(ms: Long): String = AgentDirectives.formatDuration(ms)
        fun formatFileSize(bytes: Long): String = AgentDirectives.formatFileSize(bytes)
        fun formatTimeShort(ts: Long): String = AgentDirectives.formatTimeShort(ts)
        fun timeAgo(ts: Long): String = AgentDirectives.timeAgo(ts)
    }
}
