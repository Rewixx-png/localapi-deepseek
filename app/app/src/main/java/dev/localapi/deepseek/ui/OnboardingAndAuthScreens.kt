package dev.localapi.deepseek

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun BootScreen(state: AppState) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(42.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("Local API DeepSeek", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            if (state.bootError != null) {
                Text(
                    state.bootError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                ExpressiveLoader(size = 30.dp)
            }
        }
    }
}

@Composable
fun OnboardingScreen(state: AppState) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("Local API DeepSeek", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Нативный клиент DeepSeek (V4.1 Flash и V4 Pro) на Rust с локальным OpenAI-сервером, Cloudflare Tunnel и Ubuntu-песочницей. Запросы идут напрямую.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            GroupedCardItem(index = 0, count = 2) {
                PermissionRow(
                    icon = Icons.Rounded.Notifications,
                    title = "Уведомления",
                    subtitle = "Покажет уведомление, если ответ придёт в фоне",
                    granted = state.notifGranted,
                    actionLabel = if (state.notifGranted) "Разрешено" else "Разрешить",
                    onClick = { state.requestNotifications() }
                )
            }
            GroupedCardItem(index = 1, count = 2) {
                PermissionRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = "Оптимизация батареи",
                    subtitle = "Чтобы Android не обрывал длинные ответы и сервер в фоне",
                    granted = state.batteryIgnoring,
                    actionLabel = if (state.batteryIgnoring) "Отключено" else "Отключить",
                    onClick = { state.requestBatteryExemption() }
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { state.completeOnboarding() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = CircleShape
            ) {
                Text("Далее", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "На Samsung включите фоновую работу в настройках батареи. На Xiaomi (HyperOS) включите автозапуск и режим «Без ограничений».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        FilledTonalButton(
            onClick = onClick,
            enabled = !granted,
            shape = CircleShape
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
fun TokenScreen(state: AppState) {
    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val isWeb = state.authMode == "web"

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("Подключение", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (isWeb) "Вставьте userToken с chat.deepseek.com. Проверим его запросом к /users/settings."
                else "Вставьте API-ключ. Перед сохранением проверим его запросом к /models.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            M3SegmentedRow(
                options = listOf("api" to "API-ключ", "web" to "Веб-токен"),
                selected = state.authMode,
                onSelect = {
                    state.selectAuthMode(it)
                    tokenInput = ""
                    showToken = false
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isWeb) "chat.deepseek.com → DevTools → Application → Local Storage → userToken"
                else "platform.deepseek.com → API keys → Create new key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { state.openUrl("https://github.com/Rewixx-png/localapi-deepseek/blob/main/docs/get-token.md") },
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text("Как получить API-ключ или userToken")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = {
                    tokenInput = it
                    state.tokenError = null
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                label = { Text(if (isWeb) "userToken" else "API-ключ") },
                placeholder = { Text(if (isWeb) "{\"value\":\"…\"}" else "sk-…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus()
                    state.verifyAndSaveToken(tokenInput)
                }),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Показать"
                            )
                        }
                        IconButton(onClick = {
                            val clip = state.readClipboard().trim()
                            if (clip.isEmpty()) {
                                state.showToast("Буфер обмена пуст")
                            } else {
                                tokenInput = clip
                                showToken = true
                            }
                        }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Вставить")
                        }
                    }
                }
            )

            AnimatedVisibility(visible = !state.online) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("Нет доступа к интернету", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.setOnlineEnabled(true) }) {
                            Text("Включить", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = state.tokenError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        state.tokenError ?: "",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            AnimatedVisibility(visible = isWeb) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Неофициальный веб-протокол: DeepSeek иногда блокирует такие клиенты и может ограничить аккаунт.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    focus.clearFocus()
                    state.verifyAndSaveToken(tokenInput)
                },
                enabled = state.online && !state.tokenChecking && tokenInput.trim().length >= 8,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = CircleShape
            ) {
                if (state.tokenChecking) {
                    ExpressiveLoader(
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Проверяю…")
                } else {
                    Text("Проверить и сохранить")
                }
            }

            AnimatedVisibility(visible = state.tokenVerified && state.hasToken) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Ключ подтверждён", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("Модель", "DeepSeek V4.1 Flash 1017")
                        KeyValueRow("Режим", if (state.activeMode == "web") "веб-сессия chat.deepseek.com" else "официальный API")
                        state.balance?.let { b ->
                            val sym = if (b.currency == "USD") "$" else if (b.currency == "CNY") "¥" else ""
                            KeyValueRow("Баланс", "$sym${b.total} ${if (b.currency == "USD") "" else b.currency}".trim())
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { state.openChatAfterAuth() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = CircleShape
                        ) {
                            Text("Открыть чат")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (isWeb) "DeepSeek может изменить веб-протокол без предупреждения."
                else "Ключ сохраняется локально в приватных данных приложения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
