package dev.localapi.deepseek

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PARTIAL_DIRECTIVE_RE = Regex(
    "(?:`\\s*)?\\[\\[(?:EXEC|SEND|BROWSER|BROWSE):[\\s\\S]*$",
    RegexOption.IGNORE_CASE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: AppState) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    var showSystemSheet by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ChatSummaryItem?>(null) }

    val isScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 16 } }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val itemGap = if (state.compact) 10.dp else 16.dp

    BackHandler(enabled = drawerState.isOpen || showSystemSheet) {
        if (showSystemSheet) showSystemSheet = false
        else if (drawerState.isOpen) scope.launch { drawerState.close() }
    }

    LaunchedEffect(state.messages.size, state.streaming) {
        val total = state.messages.size + if (state.streaming) 1 else 0
        if (total > 0 && !listState.isScrollInProgress) {
            listState.scrollToItem(total - 1)
        }
    }

    LaunchedEffect(state.streamContent.length, state.streamReasoning.length) {
        val total = state.messages.size + if (state.streaming) 1 else 0
        if (total > 0 && state.streaming && !listState.isScrollInProgress && !canScrollDown) {
            listState.scrollToItem(total - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 330.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.fillMaxHeight().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Local API DeepSeek", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    ExtendedFloatingActionButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            state.newDraftChat(state.agentMode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text(if (state.agentMode) "Новый чат агента" else "Новый чат") }
                    )

                    Spacer(Modifier.height(10.dp))
                    NavigationDrawerItem(
                        selected = state.agentMode,
                        onClick = {
                            scope.launch { drawerState.close() }
                            state.setAgentModeEnabled(!state.agentMode)
                        },
                        icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null) },
                        label = { Text(if (state.agentMode) "Перейти в обычные чаты" else "Режим агента (Ubuntu)") }
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (state.agentMode) "ЧАТЫ АГЕНТА" else "ИСТОРИЯ ЧАТОВ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (state.chatList.isEmpty()) {
                            item {
                                Text(
                                    if (state.agentMode) "Чатов агента пока нет." else "История пуста.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            items(state.chatList, key = { it.id }) { item ->
                                val selected = item.id == state.currentChatId
                                NavigationDrawerItem(
                                    selected = selected,
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        state.loadChat(item.id)
                                    },
                                    label = {
                                        Column {
                                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${AppState.timeAgo(item.updatedAt)} · ${item.messageCount} сообщ.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    badge = {
                                        IconButton(onClick = { deleteCandidate = item }, modifier = Modifier.size(34.dp)) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Удалить", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationDrawerItem(
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            state.settingsReturn = AppScreen.CHAT
                            state.settingsPane = SettingsPane.ROOT
                            state.screen = AppScreen.SETTINGS
                        },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("Настройки") },
                        badge = {
                            if (state.appVersion.isNotEmpty()) {
                                Text("v${state.appVersion}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.currentChatTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val tokens = state.estimateContextTokens()
                            Text(
                                "${state.modelShortLabel()} · ≈ ${formatSpaceInt(tokens)} ток.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            focus.clearFocus()
                            state.refreshChats()
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Чаты")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            focus.clearFocus()
                            showSystemSheet = true
                        }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Параметры чата")
                        }
                        FilledTonalIconButton(
                            onClick = { state.newDraftChat(state.agentMode) },
                            modifier = Modifier.padding(end = 6.dp).size(38.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Новый чат", modifier = Modifier.size(20.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isScrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                ChatComposerBar(state = state)
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = !state.online) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Нет доступа к интернету", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { state.setOnlineEnabled(true) }) {
                                    Text("Включить", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }

                    if (state.agentMode) {
                        AgentDockStrip(state)
                    }

                    if (state.messages.isEmpty() && !state.streaming) {
                        EmptyChatHero(
                            state = state,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(itemGap)
                        ) {
                            items(state.messages) { msg ->
                                when (msg.role) {
                                    "user" -> UserMessageBubble(msg, state)
                                    "tool" -> msg.toolBlock?.let { ToolExecutionCard(it) }
                                    "notice" -> msg.notice?.let { SandboxNoticeRow(it) }
                                    else -> AssistantMessageBlock(msg, state, streaming = false)
                                }
                            }
                            if (state.streaming) {
                                item(key = "streaming_assistant") {
                                    AssistantMessageBlock(
                                        m = ChatMessage(
                                            role = "assistant",
                                            content = state.streamContent,
                                            reasoning = state.streamReasoning
                                        ),
                                        state = state,
                                        streaming = true
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = canScrollDown,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            val last = (state.messages.size + if (state.streaming) 1 else 0) - 1
                            if (last >= 0) scope.launch { listState.animateScrollToItem(last) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Вниз")
                    }
                }
            }
        }
    }

    if (showSystemSheet) {
        ChatOptionsSheet(state = state, onDismiss = { showSystemSheet = false })
    }

    deleteCandidate?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${item.title}» и все сообщения будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteChat(item.id)
                    deleteCandidate = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun AgentDockStrip(state: AppState) {
    val sb = state.sandboxState
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Агент · /workspace", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (state.agentFiles.isEmpty()) "ZIP прикрепляется скрепкой"
                        else "${state.agentFiles.count { it.selected }} из ${state.agentFiles.size} файлов в контексте",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val dotColor = when {
                    sb.installing -> Color(0xFFFFCB6B)
                    sb.running -> Color(0xFF5EE68A)
                    sb.installed -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                }
                val sbText = when {
                    sb.installing -> "Загрузка ${sb.progressPercent.coerceAtLeast(0)}%"
                    sb.running -> "Ubuntu · вкл"
                    sb.installed -> "Ubuntu · готова"
                    else -> "Ubuntu · скачать"
                }
                Surface(
                    onClick = { state.refreshSandbox(); state.showSandboxSheet = true },
                    shape = CircleShape,
                    color = if (sb.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                        Text(
                            sbText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sb.running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { state.openTerminal() }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Terminal, contentDescription = "Терминал", modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { state.refreshAgentRoot(); state.showWorkspaceSheet = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Folder, contentDescription = "Workspace", modifier = Modifier.size(19.dp))
                }
            }
        }

        if (state.agentFiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.agentFiles.forEach { f ->
                    Surface(
                        onClick = { state.toggleAgentFile(f.name) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (f.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.border(
                            1.dp,
                            if (f.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (f.selected) "✓ " else "") + f.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 170.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                AppState.formatFileSize(f.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { state.deleteAgentFile(f.name) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatHero(state: AppState, modifier: Modifier = Modifier) {
    val suggestions = if (state.agentMode) {
        listOf("Проверь, что лежит в /workspace", "Запусти диагностику окружения", "Установи утилиты через install-base")
    } else {
        listOf("Объясни сложную тему простыми словами", "Напиши быстрый скрипт на Python", "Сделай план проекта по шагам")
    }
    Column(
        modifier = modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (state.agentMode) Icons.Rounded.SmartToy else Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (state.agentMode) "Агент Ubuntu готов" else "Начните диалог",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.agentMode) "Отдельная история · QEMU песочница + браузер"
            else "${state.modelShortLabel()} · ${if (state.thinking) "рассуждения включены" else "быстрый режим"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            suggestions.forEach { hint ->
                Surface(
                    onClick = { state.sendMessage(hint) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        hint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(m: ChatMessage, state: AppState) {
    val scale = LocalTextScale.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        m.images.forEach { dataUrl ->
                            val bmp = remember(dataUrl) {
                                runCatching {
                                    val b = Base64.decode(dataUrl.substringAfter(",", dataUrl), Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(b, 0, b.size)
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { state.previewImageDataUrl = dataUrl }
                                )
                            }
                        }
                    }
                }
                if (m.content.isNotEmpty()) {
                    Text(
                        m.content,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = (15.5f * scale).sp, lineHeight = (22f * scale).sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(m: ChatMessage, state: AppState, streaming: Boolean) {
    val scale = LocalTextScale.current
    var reasoningOpen by remember(m.ts, streaming) { mutableStateOf(streaming) }
    val shownText = remember(m.content, state.agentMode, streaming) {
        if (state.agentMode) {
            val cleaned = AppState.stripDirectives(m.content)
            if (streaming) cleaned.replace(PARTIAL_DIRECTIVE_RE, "").trim() else cleaned
        } else {
            m.content
        }
    }
    val browseQuery = remember(m.content, state.agentMode, streaming) {
        if (!streaming && state.agentMode) AppState.extractBrowse(m.content) else ""
    }
    val mediaBitmaps = remember(m.content, state.currentChatId, streaming) {
        if (!streaming && state.agentMode) state.readAgentMediaBitmap(m.content) else emptyList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(state.modelShortLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            if (streaming) {
                Spacer(Modifier.width(10.dp))
                ExpressiveLoader(size = 16.dp)
                Spacer(Modifier.width(6.dp))
                val stageText = when {
                    state.streamStage == "uploading" -> "Загрузка…"
                    state.search && state.streamStage == "searching" && state.streamReasoning.isEmpty() && state.streamContent.isEmpty() -> "Ищу в сети…"
                    state.streamReasoning.isNotEmpty() && state.streamContent.isEmpty() -> "Размышляю…"
                    state.streamStage == "reasoning" && state.streamContent.isEmpty() -> "Размышляю…"
                    else -> "Отвечаю…"
                }
                Text(
                    stageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (m.reasoning.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reasoningOpen = !reasoningOpen }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (reasoningOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        val dur = if (m.reasoningMs > 0) " · ${AppState.formatDuration(m.reasoningMs)}" else ""
                        Text(
                            "Размышления$dur",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = reasoningOpen) {
                        Text(
                            m.reasoning,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = (13f * scale).sp, lineHeight = (19f * scale).sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (shownText.isNotEmpty() || (streaming && m.reasoning.isEmpty())) {
            MarkdownMessageContent(
                rawText = shownText,
                textScale = scale,
                streaming = streaming,
                onCopy = { state.copyText(it) },
                onOpenUrl = { state.openUrl(it) },
                onPreviewHtml = { state.hostActions?.openHtmlPreview(it) }
            )
        }

        mediaBitmaps.forEach { (_, bmp) ->
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp))
            )
        }

        if (browseQuery.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TonalActionPill(
                label = "Открыть в браузере: $browseQuery",
                icon = Icons.Rounded.Public,
                onClick = { state.openUrl(AppState.browserBrowseUrl(browseQuery)) }
            )
        }

        if (!streaming && m.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ИСТОЧНИКИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.sources.forEach { s ->
                        Text(
                            s.title.ifEmpty { s.url },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { state.openUrl(s.url) }
                        )
                    }
                }
            }
        }

        m.error?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // Copy & metrics appear only on final completed responses, never on intermediate tool-calling steps
        if (!streaming && !m.hasToolCall && (shownText.isNotEmpty() || m.cancelled)) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (shownText.isNotEmpty()) {
                    TonalActionPill(
                        label = "Копировать",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = { state.copyText(shownText) }
                    )
                }
                if (m.elapsedMs > 0) {
                    Text(
                        AppState.formatDuration(m.elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (m.tokPerSec > 0) {
                    Text(
                        "${String.format(Locale("ru"), "%.0f", m.tokPerSec)} tok/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (m.cancelled) {
                    Text("Остановлено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(tb: ToolBlockData) {
    var open by remember(tb.id) { mutableStateOf(tb.running) }
    val ok = tb.exitCode == "0"

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0E1318),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF252D37), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF8B949E),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(tb.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF7BD88F))
                Spacer(Modifier.weight(1f))
                if (tb.running) {
                    ExpressiveLoader(size = 14.dp, color = Color(0xFF7BD88F), containerColor = Color(0xFF1A2B22))
                    Spacer(Modifier.width(6.dp))
                    Text("выполняется…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949E))
                } else {
                    Surface(
                        shape = CircleShape,
                        color = if (ok) Color(0xFF173A26) else Color(0xFF421A1D)
                    ) {
                        Text(
                            if (tb.exitCode == "—") "прервано" else "exit ${tb.exitCode}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) Color(0xFF7BD88F) else Color(0xFFFF9B9B)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = open) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(color = Color(0xFF252D37))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$ ${tb.command}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFE6EDF3)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tb.output.ifEmpty { if (tb.running) "ожидание…" else "(пусто)" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                        color = Color(0xFFC9D1D9)
                    )
                }
            }
        }
    }
}

@Composable
private fun SandboxNoticeRow(n: SandboxNotice) {
    val dot = when (n.kind) {
        "started" -> Color(0xFF5EE68A)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val time = remember(n.ts) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(n.ts)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text("${n.text} · $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatComposerBar(state: AppState) {
    val hasInput = state.composerDraft.isNotBlank() || state.pendingAttachments.isNotEmpty()
    val sendRadius by animateDpAsState(if (state.streaming) 16.dp else 24.dp, tween(200), label = "send_shape")

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.pendingAttachments.forEach { att ->
                        Box(modifier = Modifier.size(64.dp)) {
                            Image(
                                bitmap = att.preview.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { state.previewImageDataUrl = att.dataUrl }
                            )
                            IconButton(
                                onClick = { state.pendingAttachments.remove(att) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f))
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Убрать", modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(
                        onClick = {
                            if (state.agentMode) state.hostActions?.pickAgentZip()
                            else state.hostActions?.pickImages()
                        },
                        enabled = state.online,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            if (state.agentMode) Icons.Rounded.AttachFile else Icons.Rounded.Image,
                            contentDescription = if (state.agentMode) "Прикрепить ZIP" else "Прикрепить фото",
                            tint = if (state.pendingAttachments.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 140.dp)
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (state.composerDraft.isEmpty()) {
                            Text(
                                if (state.agentMode) "Задача агенту…" else "Сообщение",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = state.composerDraft,
                            onValueChange = { state.composerDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }

                    AnimatedVisibility(visible = state.streaming || hasInput) {
                        Surface(
                            onClick = {
                                if (state.streaming) state.cancelStream()
                                else if (state.online) state.sendMessage()
                            },
                            enabled = state.streaming || state.online,
                            shape = RoundedCornerShape(sendRadius),
                            color = if (state.streaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (state.streaming) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (state.streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = if (state.streaming) "Остановить" else "Отправить",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatOptionsSheet(state: AppState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var promptDraft by remember(state.systemPrompt) { mutableStateOf(state.systemPrompt) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Параметры чата", style = MaterialTheme.typography.titleLarge)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isWebSession()) {
                    TonalActionPill(
                        label = state.modelShortLabel(),
                        icon = Icons.Rounded.Bolt,
                        active = true,
                        onClick = { onDismiss(); state.showModelSheet = true }
                    )
                }
                TonalActionPill(
                    label = if (state.thinking) "Рассуждения вкл" else "Рассуждения выкл",
                    icon = Icons.Rounded.Bolt,
                    active = state.thinking,
                    onClick = { state.updateThinking(!state.thinking) }
                )
                TonalActionPill(
                    label = "Поиск",
                    icon = Icons.Rounded.Search,
                    active = state.search,
                    onClick = { state.updateSearch(!state.search) }
                )
            }

            if (state.agentMode) {
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        state.showToolsSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Инструменты агента")
                }
            }

            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                shape = RoundedCornerShape(18.dp),
                label = { Text("Системная инструкция") },
                placeholder = { Text("Например: отвечай кратко, по делу и с примерами кода") }
            )

            Button(
                onClick = {
                    state.updateSystemPrompt(promptDraft)
                    onDismiss()
                    state.showToast(if (promptDraft.trim().isNotEmpty()) "Системная инструкция сохранена" else "Инструкция очищена")
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape
            ) {
                Text("Сохранить")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatSpaceInt(v: Int): String =
    v.coerceAtLeast(0).toString().reversed().chunked(3).joinToString(" ").reversed()
