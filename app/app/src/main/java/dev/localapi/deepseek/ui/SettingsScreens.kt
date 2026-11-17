package dev.localapi.deepseek

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AppState) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(state.settingsPane.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.settingsPane != SettingsPane.ROOT) state.settingsPane = SettingsPane.ROOT
                        else state.screen = state.settingsReturn
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        state.settingsPane = SettingsPane.ROOT
                        state.screen = state.settingsReturn
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (state.settingsPane) {
                SettingsPane.ROOT -> SettingsCatalogPane(state)
                SettingsPane.TOKEN -> TokenSettingsPane(state)
                SettingsPane.SERVER -> ServerSettingsPane(state)
                SettingsPane.TUNNEL -> TunnelSettingsPane(state)
                SettingsPane.SYSTEM -> SystemSettingsPane(state)
                SettingsPane.UI -> UiSettingsPane(state)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SettingsCatalogPane(state: AppState) {
    val tokenSub = when {
        !state.hasToken -> "Токен не добавлен"
        state.isWebSession() -> "Веб-токен сохранён"
        else -> "API-ключ сохранён"
    }
    val serverSub = if (state.serverInfo.running) "127.0.0.1:${state.serverInfo.port}" else "Выключен"
    val tunnelSub = when (state.tunnelInfo.state) {
        "connected" -> state.tunnelInfo.url.ifEmpty { "Туннель работает" }
        "starting" -> "Подключаюсь…"
        "error" -> "Ошибка"
        else -> "Выключен"
    }
    val ver = if (state.appVersion.isNotEmpty()) "v${state.appVersion}" else "версия неизвестна"
    val avail = state.updateManifest?.let { " · доступно ${it.versionName}" }.orEmpty()
    val uiSub = "${state.themeMode.label} · ${state.palette.label} · текст ${state.textZoom}%"

    val items = listOf(
        Triple(SettingsPane.TOKEN, Icons.Rounded.Key, "Авторизация и ключи" to tokenSub),
        Triple(SettingsPane.UI, Icons.Rounded.Palette, "Интерфейс и цвета" to uiSub),
        Triple(SettingsPane.SERVER, Icons.Rounded.Dns, "Локальный API" to serverSub),
        Triple(SettingsPane.TUNNEL, Icons.Rounded.Public, "Cloudflare Tunnel" to tunnelSub),
        Triple(SettingsPane.SYSTEM, Icons.Rounded.Settings, "О приложении и сети" to (ver + avail))
    )

    items.forEachIndexed { index, (pane, icon, texts) ->
        GroupedCardItem(
            index = index,
            count = items.size,
            onClick = { state.settingsPane = pane }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(texts.first, style = MaterialTheme.typography.titleMedium)
                    Text(
                        texts.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TokenSettingsPane(state: AppState) {
    var newToken by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    SectionLabel("Доступ")
    GroupedCardItem(0, 2) {
        Text("Режим для нового токена", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        M3SegmentedRow(
            options = listOf("api" to "API-ключ", "web" to "Веб-токен"),
            selected = state.authMode,
            onSelect = {
                state.selectAuthMode(it)
                state.showToast(if (it == "web") "Веб-токен" else "API-ключ")
            }
        )
    }
    GroupedCardItem(1, 2) {
        val web = state.isWebSession()
        KeyValueRow("Активный режим", if (web) "веб-сессия chat.deepseek.com" else "официальный API")
        if (!web) {
            val mName = if (state.model == "deepseek-v4-pro") "V4 Pro" else "Flash 1017"
            KeyValueRow("Модель", mName + if (state.thinking) " · reasoning" else "")
        }
        val keyState = buildString {
            append(if (state.hasToken) "сохранён" else "не добавлен")
            state.balance?.let { b ->
                val sym = if (b.currency == "USD") "$" else if (b.currency == "CNY") "¥" else ""
                append(" · $sym${b.total}")
            }
        }
        KeyValueRow(if (web) "Токен" else "Ключ", keyState)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { state.revalidateToken() },
                enabled = state.online && !state.tokenChecking
            ) {
                Text(if (state.tokenChecking) "Проверяю…" else "Проверить")
            }
            TextButton(onClick = { confirmClear = true }) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (!state.isWebSession()) {
        SectionLabel("API")
        GroupedCardItem(0, 1) {
            SettingsSwitchRow(
                title = "Режим рассуждения",
                subtitle = if (state.thinking) "DeepThink включён" else "Выключен",
                icon = Icons.Rounded.Bolt,
                checked = state.thinking,
                onCheckedChange = { state.updateThinking(it) }
            )
        }
    }

    SectionLabel("Сменить токен")
    GroupedCardItem(0, 2) {
        OutlinedTextField(
            value = newToken,
            onValueChange = { newToken = it; state.tokenError = null },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            label = { Text("Новый токен") },
            placeholder = { Text(if (state.authMode == "web") "{\"value\":\"…\"}" else "sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        )
        state.tokenError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(
            onClick = {
                state.verifyAndSaveToken(newToken, stayInSettings = true)
                newToken = ""
            },
            enabled = state.online && !state.tokenChecking && newToken.trim().length >= 8
        ) {
            Text(if (state.tokenChecking) "Проверяю…" else "Сохранить")
        }
    }
    GroupedCardItem(1, 2) {
        FilledTonalButton(onClick = {
            state.openUrl("https://github.com/Rewixx-png/localapi-deepseek/blob/main/docs/get-token.md")
        }) {
            Text("Инструкция по токену")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить ключ?") },
            text = { Text("История чатов останется на устройстве.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    state.clearToken()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ServerSettingsPane(state: AppState) {
    SectionLabel("Локальный OpenAI-совместимый сервер")
    GroupedCardItem(0, if (state.serverInfo.running) 3 else 2) {
        SettingsSwitchRow(
            title = "Сервер",
            subtitle = if (state.serverInfo.running) "127.0.0.1:${state.serverInfo.port}" else "127.0.0.1:8787",
            icon = Icons.Rounded.Dns,
            checked = state.serverInfo.running,
            enabled = state.online,
            onCheckedChange = { state.toggleServer(it) }
        )
    }
    if (state.serverInfo.running) {
        GroupedCardItem(1, 3) {
            KeyValueRow("Endpoint", state.serverInfo.baseUrl, mono = true)
            KeyValueRow("Модель", if (state.isWebSession()) "deepseek-flash" else state.model, mono = true)
            KeyValueRow("Ключ", state.serverInfo.key, mono = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalActionPill("Ключ", Icons.Rounded.ContentCopy, onClick = { state.copyText(state.serverInfo.key) })
                TonalActionPill("URL", Icons.Rounded.ContentCopy, onClick = { state.copyText(state.serverInfo.baseUrl) })
                TonalActionPill("Модель", Icons.Rounded.ContentCopy, onClick = {
                    state.copyText(if (state.isWebSession()) "deepseek-flash" else state.model)
                })
            }
        }
    }
    val lastIdx = if (state.serverInfo.running) 2 else 1
    val total = lastIdx + 1
    GroupedCardItem(lastIdx, total) {
        Text(
            "Если система усыпляет сервер в фоне: на Samsung разрешите фоновую работу в настройках батареи, на Xiaomi включите автозапуск и «Без ограничений».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = { state.openAppSettings() }) {
            Text("Настройки фоновой работы")
        }
    }
}

@Composable
private fun TunnelSettingsPane(state: AppState) {
    var confirmTunnel by remember { mutableStateOf(false) }
    val active = state.tunnelInfo.state == "starting" || state.tunnelInfo.state == "connected"

    SectionLabel("Cloudflare Tunnel")
    GroupedCardItem(0, 2) {
        SettingsSwitchRow(
            title = "Туннель",
            subtitle = if (active) "Публичный HTTPS включён" else "Публичный HTTPS для локального API",
            icon = Icons.Rounded.Public,
            checked = active,
            enabled = state.online,
            onCheckedChange = { on ->
                if (on) {
                    if (!state.serverInfo.running) {
                        state.showToast("Сначала включите локальный API")
                    } else {
                        confirmTunnel = true
                    }
                } else {
                    state.stopCfTunnel()
                }
            }
        )
    }
    GroupedCardItem(1, 2) {
        M3SegmentedRow(
            options = listOf("quick" to "Быстрый", "token" to "Токен"),
            selected = state.cfMode,
            onSelect = { state.selectCfMode(it) }
        )
        AnimatedVisibility(visible = state.cfMode == "token") {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.cfToken,
                    onValueChange = { state.cfToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Токен туннеля") },
                    placeholder = { Text("eyJhIjoi…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = state.cfHost,
                    onValueChange = { state.cfHost = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Публичный адрес") },
                    placeholder = { Text("api.example.com") },
                    singleLine = true
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val statusLabel = when (state.tunnelInfo.state) {
            "starting" -> if (state.tunnelInfo.url.isNotEmpty()) "Подключаюсь…" else "Запускаю…"
            "connected" -> "Туннель работает"
            "error" -> "Ошибка: ${state.tunnelInfo.error}"
            else -> "Выключен"
        }
        KeyValueRow("Статус", statusLabel)
        if (state.tunnelInfo.url.isNotEmpty()) {
            KeyValueRow("URL", state.tunnelInfo.url, mono = true)
            Spacer(Modifier.height(8.dp))
            TonalActionPill("Копировать URL", Icons.Rounded.ContentCopy, onClick = { state.copyText(state.tunnelInfo.url) })
        }
    }

    if (confirmTunnel) {
        AlertDialog(
            onDismissRequest = { confirmTunnel = false },
            title = { Text("Cloudflare Tunnel") },
            text = {
                Text("Локальный API станет доступен из интернета по HTTPS. Любой, у кого есть ключ, сможет расходовать ваш токен DeepSeek.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTunnel = false
                    state.startCfTunnel()
                }) { Text("Включить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTunnel = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SystemSettingsPane(state: AppState) {
    SectionLabel("Сеть")
    GroupedCardItem(0, 1) {
        SettingsSwitchRow(
            title = "Доступ к интернету",
            subtitle = if (state.online) "Запросы разрешены" else "Запросы заблокированы",
            icon = Icons.Rounded.WifiOff,
            checked = state.online,
            onCheckedChange = { state.setOnlineEnabled(it) }
        )
    }

    SectionLabel("Обновления и о проекте")
    GroupedCardItem(0, 3) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Обновления", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(state.updateStatusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { state.checkUpdateManual() }) {
                Text("Проверить")
            }
        }
    }
    GroupedCardItem(1, 3) {
        SettingsSwitchRow(
            title = "Напоминать об обновлениях",
            subtitle = "Показывать окно и уведомления о новых версиях",
            icon = Icons.Rounded.Notifications,
            checked = state.updateRemind,
            onCheckedChange = { state.setUpdateRemindEnabled(it) }
        )
    }
    GroupedCardItem(2, 3, onClick = { state.openUrl("https://github.com/Rewixx-png/localapi-deepseek") }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Исходный код", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("github.com/Rewixx-png/localapi-deepseek", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.appVersion.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Local API DeepSeek · v${state.appVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun UiSettingsPane(state: AppState) {
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val palettes = remember(dynamicSupported) {
        AppPalette.entries.filter { it != AppPalette.DYNAMIC || dynamicSupported }
    }

    SectionLabel("Оформление")
    GroupedCardItem(0, 3) {
        Text("Тема", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        M3SegmentedRow(
            options = AppThemeMode.entries.map { it to it.label },
            selected = state.themeMode,
            onSelect = { state.updateThemeMode(it) }
        )
    }
    GroupedCardItem(1, 3) {
        Text("Палитра (Material You)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            if (state.palette == AppPalette.DYNAMIC && dynamicSupported) "Динамические цвета с обоев устройства"
            else "Выбранная палитра Material 3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.chunked(3).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { pal ->
                        val active = state.palette == pal || (state.palette == AppPalette.DYNAMIC && !dynamicSupported && pal == AppPalette.BLUE)
                        val c1 = if (pal == AppPalette.DYNAMIC) MaterialTheme.colorScheme.primary else pal.swatchPrimary
                        val c2 = if (pal == AppPalette.DYNAMIC) MaterialTheme.colorScheme.tertiary else pal.swatchSecondary
                        val c3 = if (pal == AppPalette.DYNAMIC) MaterialTheme.colorScheme.primaryContainer else pal.swatchContainer
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = if (active) 2.dp else 0.dp,
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { state.updatePalette(pal) },
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(14.dp).clip(CircleShape).background(c1))
                                    Box(Modifier.offset(x = (-4).dp).size(14.dp).clip(CircleShape).background(c2))
                                    Box(Modifier.offset(x = (-8).dp).size(14.dp).clip(CircleShape).background(c3))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    pal.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    GroupedCardItem(2, 3) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Размер текста", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("${state.textZoom}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = state.textZoom.toFloat(),
            onValueChange = { state.updateTextZoom(it.roundToInt()) },
            valueRange = 85f..150f
        )
        val scale = state.textZoom / 100f
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Как выглядит текст в чате?",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = (15 * scale).sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            "Вот так. Жирный текст, код и формулы.",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = (15 * scale).sp
                        )
                    }
                }
            }
        }
    }

    SectionLabel("Плотность")
    GroupedCardItem(0, 1) {
        SettingsSwitchRow(
            title = "Компактный режим",
            subtitle = "Меньше отступов в ленте сообщений и списках",
            icon = Icons.Rounded.ViewAgenda,
            checked = state.compact,
            onCheckedChange = { state.updateCompact(it) }
        )
    }
}
