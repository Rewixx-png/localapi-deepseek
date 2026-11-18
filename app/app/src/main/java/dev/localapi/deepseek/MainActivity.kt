package dev.localapi.deepseek

import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var appState: AppState

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) appState.addImagesFromUris(uris)
    }

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) appState.importAgentZip(uri)
    }

    private val rootPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) appState.setSafRoot(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SandboxProxy.start()
        appState = AppState(this).apply {
            hostActions = object : AppState.HostActions {
                override fun pickImages() {
                    runCatching { imagePicker.launch("image/*") }
                        .onFailure { appState.showToast("Нет приложения для выбора фото") }
                }

                override fun pickAgentZip() {
                    runCatching {
                        zipPicker.launch(arrayOf("*/*"))
                    }.onFailure { appState.showToast("Нет приложения для выбора ZIP") }
                }

                override fun chooseAgentRoot() {
                    runCatching { rootPicker.launch(null) }
                        .onFailure { appState.showToast("Нет системного выбора папки") }
                }

                override fun openHtmlPreview(html: String) {
                    showHtmlPreview(html)
                }

                override fun finishApp() {
                    finish()
                }
            }
        }
        appState.boot()
        handleInstallIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!appState.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            LocalApiTheme(
                themeMode = appState.themeMode,
                palette = appState.palette,
                textScale = appState.textZoom / 100f,
                uiScale = appState.uiScale / 100f
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (appState.screen) {
                        AppScreen.BOOT -> BootScreen(appState)
                        AppScreen.ONBOARDING -> OnboardingScreen(appState)
                        AppScreen.TOKEN -> TokenScreen(appState)
                        AppScreen.CHAT -> ChatScreen(appState)
                        AppScreen.SETTINGS -> SettingsScreen(appState)
                        AppScreen.TERMINAL -> TerminalScreen(appState)
                    }

                    ModelConfigSheet(appState)
                    AgentToolsSheet(appState)
                    WorkspaceSheet(appState)
                    SandboxSheet(appState)
                    JournalSheet(appState)
                    UpdateAlertDialog(appState)
                    ImageViewerOverlay(appState)

                    AnimatedVisibility(
                        visible = appState.toastMessage != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 92.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shadowElevation = 4.dp
                        ) {
                            Text(
                                text = appState.toastMessage.orEmpty(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appState.onResume()
    }

    override fun onPause() {
        appState.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
        appState.onResume()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        appState.refreshPermissions()
    }

    override fun onDestroy() {
        appState.destroy()
        super.onDestroy()
    }

    private fun handleInstallIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra("install_result", false)) return
        intent.removeExtra("install_result")
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        var message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(this, "Обновление установлено", Toast.LENGTH_SHORT).show()
            return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(confirm)
                    return
                } catch (e: Exception) {
                    message = "Не удалось открыть окно установки: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        appState.updateBusy = false
        appState.updateError = Updater.installErrorText(status, message)
        appState.showUpdateDialog = true
    }

    private fun showHtmlPreview(html: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val preview = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme
                    return scheme != "http" && scheme != "https" && scheme != "data" && scheme != "about"
                }
            }
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val close = ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close_small)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                val pad = Math.round(12 * resources.displayMetrics.density)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { dialog.dismiss() }
            }
            val lp = FrameLayout.LayoutParams(
                Math.round(48 * resources.displayMetrics.density),
                Math.round(48 * resources.displayMetrics.density),
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, Math.round(24 * resources.displayMetrics.density), Math.round(12 * resources.displayMetrics.density), 0)
            }
            addView(close, lp)
        }
        dialog.setOnDismissListener {
            frame.removeAllViews()
            preview.destroy()
        }
        dialog.setContentView(frame)
        dialog.show()
    }
}
