package dev.localapi.deepseek

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigSheet(state: AppState) {
    if (!state.showModelSheet) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val web = state.isWebSession()

    ModalBottomSheet(
        onDismissRequest = { state.showModelSheet = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Модель и параметры ИИ", style = MaterialTheme.typography.titleLarge)

            if (!web) {
                GroupedCardItem(0, 1, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Text("Модель", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    M3SegmentedRow(
                        options = listOf("deepseek-flash" to "Flash 1017", "deepseek-v4-pro" to "V4 Pro"),
                        selected = state.model,
                        onSelect = { state.updateModel(it) }
                    )
                }
            }

            val totalRows = if (state.thinking && !web) 3 else 2
            GroupedCardItem(0, totalRows, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                SettingsSwitchRow(
                    title = "Рассуждения",
                    subtitle = if (state.thinking) "Глубокое обдумывание (DeepThink)" else "Быстрый прямой ответ",
                    icon = Icons.Rounded.Bolt,
                    checked = state.thinking,
                    onCheckedChange = { state.updateThinking(it) }
                )
            }
            GroupedCardItem(1, totalRows, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                SettingsSwitchRow(
                    title = "Поиск в интернете",
                    subtitle = if (web) "Веб-поиск в чате" else "DeepSeek Search (платно за запрос)",
                    icon = Icons.Rounded.Search,
                    checked = state.search,
                    onCheckedChange = { state.updateSearch(it) }
                )
            }
            if (state.thinking && !web) {
                GroupedCardItem(2, totalRows, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Text("Усилие рассуждений", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    M3SegmentedRow(
                        options = listOf("low" to "low", "high" to "high", "max" to "max"),
                        selected = state.effort,
                        onSelect = { state.updateEffort(it) }
                    )
                }
            }
            Button(
                onClick = { state.showModelSheet = false },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                shape = CircleShape
            ) { Text("Готово") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentToolsSheet(state: AppState) {
    if (!state.showToolsSheet) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { state.showToolsSheet = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("Инструменты агента", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Команды выполняются в Ubuntu внутри /workspace. Архивы до 40 МБ прикрепляются скрепкой.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            val entries = listOf(
                Triple(Icons.Rounded.Memory, "Песочница Ubuntu") {
                    state.showToolsSheet = false
                    state.refreshSandbox()
                    state.showSandboxSheet = true
                },
                Triple(Icons.Rounded.Terminal, "Терминал Ubuntu") {
                    state.showToolsSheet = false
                    state.openTerminal()
                },
                Triple(Icons.Rounded.Folder, "Папка Workspace") {
                    state.showToolsSheet = false
                    state.refreshAgentRoot()
                    state.showWorkspaceSheet = true
                },
                Triple(Icons.Rounded.Description, "Журнал агента") {
                    state.showToolsSheet = false
                    state.showJournalSheet = true
                }
            )

            entries.forEachIndexed { idx, (icon, label, action) ->
                GroupedCardItem(
                    index = idx,
                    count = entries.size,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = action
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(state: AppState) {
    if (!state.showWorkspaceSheet) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { state.showWorkspaceSheet = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Workspace", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.agentRootConfigured) "Папка на устройстве: ${state.agentRootName.ifEmpty { "выбрана" }}"
                else "Папка приложения (приватно)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { state.useInternalWorkspace() },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape
            ) { Text("Папка приложения") }
            if (state.agentRootConfigured) {
                FilledTonalButton(
                    onClick = { state.openAgentFolder() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть папку на устройстве")
                }
            }
            FilledTonalButton(
                onClick = {
                    state.showWorkspaceSheet = false
                    state.hostActions?.chooseAgentRoot()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape
            ) { Text("Выбрать папку на устройстве") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxSheet(state: AppState) {
    if (!state.showSandboxSheet) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sb = state.sandboxState
    var cmdInput by remember { mutableStateOf("") }
    val logScroll = rememberScrollState()

    LaunchedEffect(sb.log) { logScroll.scrollTo(logScroll.maxValue) }

    ModalBottomSheet(
        onDismissRequest = { state.showSandboxSheet = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Песочница Ubuntu", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val pillColor = if (sb.running) Color(0xFF1F6E3D) else MaterialTheme.colorScheme.surfaceContainerHighest
                val pillFg = if (sb.running) Color(0xFFD9FFE4) else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(shape = CircleShape, color = pillColor) {
                    Text(
                        when {
                            sb.installing -> "Загрузка…"
                            sb.running -> "Работает"
                            sb.installed -> "Готова"
                            else -> "Не скачана"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = pillFg
                    )
                }
            }
            Text(
                "Ubuntu Base (arm64) в QEMU без GUI (~127 МБ). Работает через TCG без KVM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val ramChoices = listOf(1024, 2048, 3072, 4096, 6144).filter { it <= sb.maxRamMb }.ifEmpty { listOf(sb.maxRamMb) }
            val coreChoices = listOf(1, 2, 4, 6, 8).filter { it <= sb.maxCores }.ifEmpty { listOf(sb.maxCores) }

            Text("Память RAM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            M3SegmentedRow(
                options = ramChoices.map { it to (if (it >= 1024) "${it / 1024} ГБ" else "$it МБ") },
                selected = sb.ramMb,
                onSelect = { state.updateSandboxResources(it, sb.cores) }
            )
            Text("Ядра CPU", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            M3SegmentedRow(
                options = coreChoices.map { it to "$it" },
                selected = sb.cores,
                onSelect = { state.updateSandboxResources(sb.ramMb, it) }
            )

            if (sb.installing && sb.progressPercent >= 0) {
                LinearProgressIndicator(
                    progress = { sb.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape)
                )
                Text("Загрузка: ${sb.progressPercent}%", style = MaterialTheme.typography.labelSmall)
            }

            if (sb.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { state.installSandboxBase() },
                        enabled = !sb.installingBase,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                sb.installingBase -> "Установка…"
                                sb.baseInstalled -> "Обновить пакеты"
                                else -> "Пакеты (Python…)"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = { state.testSandboxNetwork() },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Сеть", maxLines = 1)
                    }
                }
                if (sb.installedTools.isNotEmpty()) {
                    Text(
                        "Установлено: ${sb.installedTools}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (sb.networkStatus.isNotEmpty()) {
                    Text(
                        "Сеть: ${sb.networkStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (sb.log.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0A0D10),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp)
                ) {
                    SelectionContainer {
                        Text(
                            sb.log,
                            modifier = Modifier.verticalScroll(logScroll).padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = Color(0xFFD7D7D7)
                        )
                    }
                }
            }

            if (sb.running) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Команда в Ubuntu") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (cmdInput.isNotBlank()) { state.writeTerminal(cmdInput); cmdInput = "" }
                    }),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (cmdInput.isNotBlank()) { state.writeTerminal(cmdInput); cmdInput = "" }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить")
                        }
                    }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!sb.installed) {
                    Button(
                        onClick = { state.installSandbox() },
                        enabled = !sb.installing,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (sb.installing) "Скачиваю…" else "Скачать")
                    }
                } else {
                    if (!sb.running) {
                        Button(onClick = { state.startSandbox() }, modifier = Modifier.weight(1f), shape = CircleShape) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Запустить")
                        }
                    } else {
                        Button(
                            onClick = { state.stopSandbox() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                            modifier = Modifier.weight(1f),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Остановить")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            state.showSandboxSheet = false
                            state.openTerminal()
                        },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Терминал")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalSheet(state: AppState) {
    if (!state.showJournalSheet) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { state.showJournalSheet = false },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Журнал агента", style = MaterialTheme.typography.titleLarge)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 360.dp)
            ) {
                SelectionContainer {
                    Text(
                        if (state.agentJournal.isEmpty()) "Журнал пуст." else state.agentJournal.joinToString("\n"),
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { state.clearJournal() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = {
                    state.copyText(state.agentJournal.joinToString("\n").ifEmpty { "Журнал пуст." })
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Скопировать")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun UpdateAlertDialog(state: AppState) {
    val m = state.updateManifest
    if (!state.showUpdateDialog || m == null) return

    AlertDialog(
        onDismissRequest = { if (!state.updateBusy) state.showUpdateDialog = false },
        title = { Text("Доступно обновление ${m.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.notes.ifEmpty { "Доступна новая версия приложения." }, style = MaterialTheme.typography.bodyMedium)
                if (state.updateProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { state.updateProgress / 100f },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape)
                    )
                    Text(
                        if (state.updateInstalling) "100% · Подтвердите установку" else "${state.updateProgress}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                state.updateError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { state.downloadUpdate() },
                enabled = !state.updateBusy && !state.updateInstalling
            ) { Text(if (state.updateBusy) "Загрузка…" else "Обновить") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        state.setUpdateRemindEnabled(false)
                        state.showUpdateDialog = false
                    },
                    enabled = !state.updateBusy
                ) { Text("Не напоминать") }
                TextButton(
                    onClick = { state.showUpdateDialog = false },
                    enabled = !state.updateBusy
                ) { Text("Позже") }
            }
        }
    )
}

@Composable
fun ImageViewerOverlay(state: AppState) {
    val dataUrl = state.previewImageDataUrl ?: return
    val bmp = remember(dataUrl) {
        runCatching {
            val b64 = dataUrl.substringAfter(",", dataUrl)
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable { state.previewImageDataUrl = null },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(20.dp)
            )
        }
        IconButton(
            onClick = { state.previewImageDataUrl = null },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(state: AppState) {
    var cmd by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(state.terminalOutput) { scroll.scrollTo(scroll.maxValue) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF06080B)) {
        Column(modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            TopAppBar(
                title = { Text("Ubuntu", color = Color(0xFFE6EDF3)) },
                navigationIcon = {
                    IconButton(onClick = { state.screen = AppScreen.CHAT }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Color(0xFFE6EDF3))
                    }
                },
                actions = {
                    IconButton(onClick = { state.stopSandbox(); state.showToast("Песочница остановлена") }) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Остановить", tint = Color(0xFFFF9B9B))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1117))
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = state.terminalOutput.ifEmpty { "Ожидание вывода QEMU…" },
                        modifier = Modifier.verticalScroll(scroll).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = Color(0xFFD7DCE2)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$", color = Color(0xFF7BD88F), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Команда", color = Color(0xFF8B949E)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (cmd.isNotEmpty()) { state.writeTerminal(cmd); cmd = "" }
                    })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { if (cmd.isNotEmpty()) { state.writeTerminal(cmd); cmd = "" } },
                    modifier = Modifier.clip(CircleShape).background(Color(0xFF1F2630))
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = Color(0xFFE6EDF3))
                }
            }
        }
    }
}
