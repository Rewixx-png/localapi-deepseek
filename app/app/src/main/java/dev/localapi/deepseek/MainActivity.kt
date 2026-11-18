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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                var lastToast by remember { mutableStateOf("") }
                LaunchedEffect(appState.toastMessage) {
                    appState.toastMessage?.let { lastToast = it }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = appState.screen,
                        transitionSpec = {
                            when {
                                targetState == AppScreen.SETTINGS || targetState == AppScreen.TERMINAL ->
                                    (slideInHorizontally(tween(240)) { it / 5 } + fadeIn(tween(220))) togetherWith
                                        fadeOut(tween(150))
                                initialState == AppScreen.SETTINGS || initialState == AppScreen.TERMINAL ->
                                    fadeIn(tween(200)) togetherWith
                                        (slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(150)))
                                else ->
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220))) togetherWith
                                        fadeOut(tween(160))
                            }
                        },
                        label = "screen_nav_anim"
                    ) { scr ->
                        when (scr) {
                            AppScreen.BOOT -> BootScreen(appState)
                            AppScreen.ONBOARDING -> OnboardingScreen(appState)
                            AppScreen.TOKEN -> TokenScreen(appState)
                            AppScreen.CHAT -> ChatScreen(appState)
                            AppScreen.SETTINGS -> SettingsScreen(appState)
                            AppScreen.TERMINAL -> TerminalScreen(appState)
                        }
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
                        enter = slideInVertically(
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                        ) { it / 2 } + scaleIn(initialScale = 0.92f) + fadeIn(tween(180)),
                        exit = slideOutVertically(tween(160)) { it / 2 } + scaleOut(targetScale = 0.94f) + fadeOut(tween(150)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 92.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = appState.toastMessage ?: lastToast,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
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
