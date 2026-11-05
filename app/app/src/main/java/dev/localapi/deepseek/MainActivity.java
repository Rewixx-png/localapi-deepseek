package dev.localapi.deepseek;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.local";
    private static final String ORIGIN = "https://" + HOST + "/";
    private static final int REQ_NOTIFICATIONS = 7;
    private static final int REQ_AGENT_ZIP = 22;
    private static final int REQ_AGENT_ROOT = 23;

    private static final String CHANNEL_ID = "replies";
    private static final int NOTIFICATION_ID = 41;

    private WebView web;
    private LinearLayout root;
    private NativeComposer composer;
    private Updater updater;
    private CloudflareTunnel tunnel;
    private SandboxManager sandbox;
    private volatile boolean foreground = false;
    private boolean pageReady;
    private String pendingInstallError;
    private volatile String pendingAgentChatId;
    private volatile String pendingAgentTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The native engine owns the token, history and streaming. Opening it is
        // a couple of file reads, so it is safe to do before the first frame.
        Rust.nativeOpen(getFilesDir().getAbsolutePath());

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0E13"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);

        web.setWebViewClient(new AssetsClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        sandbox = new SandboxManager(this, web);
        tunnel = CloudflareTunnel.get(this);
        tunnel.attachWebView(web);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111318"));

        // Android 15 draws apps edge-to-edge when targetSdk >= 35. Opt into that
        // explicitly on API 30+ and own the insets ourselves: adjustResize is a
        // no-op under edge-to-edge, so the keyboard would otherwise cover the
        // composer. On older APIs the manifest's adjustResize still resizes the
        // window, which is the fallback path.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

            final int[] barsInsets = new int[4];
            final int[] imeBottom = {0};
            final int[] lastImeCss = {-1};
            final boolean[] animating = {false};

            final Runnable applyBars = () -> root.setPadding(
                    barsInsets[0], barsInsets[1], barsInsets[2], barsInsets[3]);
            // The IME is handled by translationY only: no requestLayout and no
            // bottom margin, so the bar moves on the render thread in lockstep
            // with the keyboard.
            final Runnable applyIme = () -> {
                int offset = Math.max(0, imeBottom[0] - barsInsets[3]);
                if (composer != null) composer.setImeOffset(offset);
            };
            final Runnable notifyIme = () -> {
                int offset = Math.max(0, imeBottom[0] - barsInsets[3]);
                int css = Math.round(offset / getResources().getDisplayMetrics().density);
                if (css == lastImeCss[0]) return;
                lastImeCss[0] = css;
                if (web != null) {
                    web.evaluateJavascript(
                            "window.__imeHeight && window.__imeHeight(" + css + ")",
                            null);
                }
            };
            final Runnable finishAnimation = () -> {
                // A cancelled animation may never dispatch onEnd on API 30.
                animating[0] = false;
                applyBars.run();
                applyIme.run();
                notifyIme.run();
            };

            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                barsInsets[0] = bars.left;
                barsInsets[1] = bars.top;
                barsInsets[2] = bars.right;
                barsInsets[3] = bars.bottom;
                imeBottom[0] = ime.bottom;
                applyBars.run();
                if (!animating[0]) {
                    applyIme.run();
                    notifyIme.run();
                }
                return insets;
            });

            root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override
                public void onPrepare(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                    animating[0] = true;
                    root.removeCallbacks(finishAnimation);
                    // Fallback for API 30 builds that occasionally drop the
                    // final animation callback. Do not wait a full second:
                    // the keyboard animation is normally ~300 ms.
                    long fallbackMs = Math.max(400L, animation.getDurationMillis() + 100L);
                    root.postDelayed(finishAnimation, fallbackMs);
                }

                @Override
                public WindowInsets onProgress(WindowInsets insets,
                        List<WindowInsetsAnimation> runningAnimations) {
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    imeBottom[0] = ime.bottom;
                    // Translation is a render-thread property: no layout pass
                    // per frame, so the bar cannot lag behind the keyboard.
                    // The WebView spacer must advance on the same frames;
                    // updating it only in onEnd makes the chat jump when the
                    // keyboard finishes opening/closing.
                    applyIme.run();
                    notifyIme.run();
                    return insets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                    root.removeCallbacks(finishAnimation);
                    animating[0] = false;
                    applyBars.run();
                    applyIme.run();
                    notifyIme.run();
                }
            });
        }

        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        composer = new NativeComposer(this, web);
        root.addView(composer.view(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        updater = new Updater(this, web);
        updater.onLaunch();
        handleInstallIntent(getIntent());
        web.loadUrl(ORIGIN + "index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (web != null) {
            web.evaluateJavascript("window.__onResume && window.__onResume()", null);
        }
        if (updater != null) {
            updater.onResume();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallIntent(intent);
        if (updater != null) {
            updater.onResume();
        }
    }

    @Override
    protected void onPause() {
        // Only notifications depend on foreground. The Rust engine, the local
        // API and the tunnel stay alive in ServerService; do not stop them and
        // do not pause WebView timers here.
        foreground = false;
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AGENT_ROOT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    SafAgentWorkspace.setRoot(MainActivity.this, data.getData());
                    if (web != null) {
                        web.evaluateJavascript(
                                "window.__agentRootUpdated && window.__agentRootUpdated()",
                                null);
                    }
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "Не удалось сохранить папку workspace";
                    }
                    Toast.makeText(MainActivity.this, message,
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (requestCode == REQ_AGENT_ZIP) {
            String chatId = pendingAgentChatId;
            String title = pendingAgentTitle;
            pendingAgentChatId = null;
            pendingAgentTitle = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && chatId != null) {
                Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        String name = AgentWorkspace.copyZip(
                                MainActivity.this, chatId, uri);
                        String summary = "ZIP добавлен в /workspace: " + name;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, summary,
                                    Toast.LENGTH_SHORT).show();
                            if (web != null) {
                                web.evaluateJavascript(
                                        "window.__agentFilesUpdated && window.__agentFilesUpdated("
                                                + JSONObject.quote(chatId) + ")",
                                        null);
                            }
                        });
                    } catch (Exception e) {
                        String message = e.getMessage();
                        if (e instanceof UnsupportedOperationException) {
                            message = "Выбранная папка workspace не поддерживает "
                                    + "создание файлов. Выберите папку в памяти "
                                    + "устройства (например, Documents)";
                        } else if (message == null || message.trim().isEmpty()) {
                            message = "Не удалось распаковать ZIP ("
                                    + e.getClass().getSimpleName() + ")";
                        }
                        final String detail = message;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, detail,
                                    Toast.LENGTH_LONG).show();
                            if (web != null) {
                                web.evaluateJavascript(
                                        "window.__agentError && window.__agentError("
                                                + JSONObject.quote(chatId) + ","
                                                + JSONObject.quote(detail) + ")",
                                        null);
                            }
                        });
                    }
                }, "agent-zip").start();
            }
            return;
        }
        if (composer != null) {
            composer.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS && web != null) {
            web.evaluateJavascript("window.__onResume && window.__onResume()", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(
                "window.__onAndroidBack ? window.__onAndroidBack() : false",
                handled -> {
                    if (!"true".equals(handled)) {
                        finish();
                    }
                });
    }

    private void handleInstallIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("install_result", false)) return;
        intent.removeExtra("install_result");
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            pendingInstallError = null;
            Toast.makeText(this, "Обновление установлено", Toast.LENGTH_SHORT).show();
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(confirm);
                    return;
                } catch (Exception e) {
                    String reason = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                    message = "Не удалось открыть окно установки: " + reason;
                }
            }
        }
        pendingInstallError = Updater.installErrorText(status, message);
        showPendingInstallError();
    }

    private void showPendingInstallError() {
        if (!pageReady || pendingInstallError == null || web == null) return;
        String message = pendingInstallError;
        pendingInstallError = null;
        web.evaluateJavascript("window.__updateError && window.__updateError("
                + JSONObject.quote(message) + ")", null);
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (focus != null) {
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        } else if (web != null && imm != null) {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Runs a model-generated HTML block in a separate WebView with no
     * JavascriptInterface. The preview cannot reach Native.*, app files or
     * local storage; it is a throwaway full-screen dialog.
     */
    private void showHtmlPreview(String html) {
        hideIme();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        WebView preview = new WebView(this);
        WebSettings settings = preview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        preview.setBackgroundColor(Color.BLACK);
        preview.setOverScrollMode(View.OVER_SCROLL_NEVER);
        preview.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme)
                        || "data".equals(scheme) || "about".equals(scheme));
            }
        });
        preview.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close_small);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setColorFilter(Color.WHITE);
        close.setContentDescription("Закрыть");
        int padding = dp(12);
        close.setPadding(padding, padding, padding, padding);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(24), dp(12), 0);
        frame.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            frame.removeAllViews();
            preview.destroy();
        });
        dialog.setContentView(frame);
        dialog.show();
    }

    private boolean notificationsGranted() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ignoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    protected void onDestroy() {
        if (tunnel != null) {
            tunnel.detachWebView();
        }
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface
        public String status() {
            return Rust.nativeStatus();
        }

        @JavascriptInterface
        public String setToken(String token, String mode) {
            String raw = Rust.nativeSetToken(token, mode);
            try {
                if (new JSONObject(raw).optBoolean("ok")) {
                    stopLocalService();
                }
            } catch (Exception ignored) {
            }
            return raw;
        }

        @JavascriptInterface
        public String revalidate() {
            return Rust.nativeRevalidate();
        }

        @JavascriptInterface
        public String setOnline(boolean online) {
            String raw = Rust.nativeSetOnline(online);
            Updater.setPrefs(MainActivity.this, online,
                    Updater.remindersEnabled(MainActivity.this));
            if (!online) {
                stopLocalService();
            }
            return raw;
        }

        @JavascriptInterface
        public String clearToken() {
            String raw = Rust.nativeClearToken();
            stopLocalService();
            return raw;
        }

        @JavascriptInterface
        public String listChats(String kind) {
            return Rust.nativeListChats(kind);
        }

        @JavascriptInterface
        public String newChat(String kind) {
            return Rust.nativeNewChat(kind);
        }

        @JavascriptInterface
        public String useInternalAgentWorkspace() {
            SafAgentWorkspace.clearRoot(MainActivity.this);
            return agentOk("ok");
        }

        @JavascriptInterface
        public String sandboxStatus() {
            try {
                return agentOk(sandbox.status());
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public void installSandbox() {
            sandbox.install();
        }

        @JavascriptInterface
        public String sandboxConfig(int ramMb, int cores) {
            try {
                return agentOk(sandbox.setConfig(ramMb, cores));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public void startSandbox(String chatId) {
            sandbox.start(chatId);
        }

        @JavascriptInterface
        public void stopSandbox() {
            sandbox.stop();
        }

        @JavascriptInterface
        public void sandboxExec(String id, String command) {
            sandbox.exec(id, command);
        }

        @JavascriptInterface
        public void sandboxWrite(String text) {
            sandbox.write(text);
        }

        @JavascriptInterface
        public void chooseAgentRoot() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            runOnUiThread(() -> {
                try {
                    startActivityForResult(intent, REQ_AGENT_ROOT);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Нет системного выбора папки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String agentRootInfo() {
            try {
                return agentOk(SafAgentWorkspace.info(MainActivity.this));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public String ensureAgentFolder(String chatId, String title) {
            try {
                if (!SafAgentWorkspace.hasRoot(MainActivity.this)) return agentOk(null);
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("name", SafAgentWorkspace.chatDir(
                        MainActivity.this, chatId, title, true).getName());
                return agentOk(data);
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public void openAgentFolder(String chatId, String title) {
            try {
                if (!SafAgentWorkspace.hasRoot(MainActivity.this)) return;
                androidx.documentfile.provider.DocumentFile dir =
                        SafAgentWorkspace.chatDir(MainActivity.this, chatId, title, true);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(dir.getUri(), "vnd.android.document/directory");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> {
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "Файловый менеджер не открыл папку; она лежит в выбранном workspace",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        e.getMessage() == null ? "Нет доступа к папке" : e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public String renameAgentFolder(String chatId, String title) {
            try {
                return agentOk(SafAgentWorkspace.renameChatDir(
                        MainActivity.this, chatId, title));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public void pickAgentZip(String chatId, String title) {
            pendingAgentChatId = chatId;
            pendingAgentTitle = title;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/x-zip-compressed"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> {
                try {
                    // Open the system document picker directly, like the
                    // official app: no chooser list with Drive/Files apps.
                    startActivityForResult(intent, REQ_AGENT_ZIP);
                } catch (Exception e) {
                    pendingAgentChatId = null;
                    Toast.makeText(MainActivity.this,
                            "Нет приложения для выбора ZIP", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String listAgentFiles(String chatId) {
            try {
                return agentOk(AgentWorkspace.list(MainActivity.this, chatId));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public String readAgentMedia(String chatId, String name) {
            try {
                return agentOk(AgentWorkspace.readMedia(
                        MainActivity.this, chatId, name, 12 * 1024 * 1024));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public String readAgentFile(String chatId, String name) {
            try {
                return agentOk(AgentWorkspace.read(
                        MainActivity.this, chatId, name, 128 * 1024));
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public String deleteAgentFile(String chatId, String name) {
            try {
                boolean deleted = AgentWorkspace.delete(MainActivity.this, chatId, name);
                if (!deleted) throw new IOException("Не удалось удалить файл");
                return agentOk("Файл удалён");
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        @JavascriptInterface
        public String clearAgentWorkspace(String chatId) {
            try {
                AgentWorkspace.clear(MainActivity.this, chatId);
                return agentOk("Workspace очищен");
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        private String agentOk(Object data) {
            try {
                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("data", data);
                return root.toString();
            } catch (Exception e) {
                return agentFail(e);
            }
        }

        private String agentFail(Exception e) {
            try {
                JSONObject error = new JSONObject();
                error.put("kind", "agent");
                error.put("message", e.getMessage() == null ? "Ошибка workspace" : e.getMessage());
                JSONObject root = new JSONObject();
                root.put("ok", false);
                root.put("error", error);
                return root.toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":{\"kind\":\"agent\",\"message\":\"Ошибка workspace\"}}";
            }
        }

        @JavascriptInterface
        public String getChat(String id) {
            return Rust.nativeGetChat(id);
        }

        @JavascriptInterface
        public String deleteChat(String id) {
            return Rust.nativeDeleteChat(id);
        }

        @JavascriptInterface
        public String send(String chatId, String message, String model, String effort, boolean thinking, boolean search, String imagesJson, String systemPrompt) {
            String raw = Rust.nativeSend(chatId, message, model, effort, thinking, search, imagesJson, systemPrompt);
            watch(raw);
            return raw;
        }

        @JavascriptInterface
        public void generateTitle(String chatId, String text) {
            new Thread(() -> {
                String raw = Rust.nativeGenerateTitle(chatId, text);
                try {
                    JSONObject root = new JSONObject(raw);
                    if (!root.optBoolean("ok")) return;
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) return;
                    String title = data.optString("title");
                    if (title.isEmpty()) return;
                    runOnUiThread(() -> web.evaluateJavascript(
                            "window.__titleUpdated && window.__titleUpdated("
                                    + JSONObject.quote(chatId) + ","
                                    + JSONObject.quote(title) + ")",
                            null));
                } catch (Exception ignored) {
                }
            }, "chat-title").start();
        }

        @JavascriptInterface
        public String startServer(int port) {
            String raw = Rust.nativeStartServer(port);
            try {
                if (new JSONObject(raw).optBoolean("ok")) {
                    runOnUiThread(() -> {
                        startLocalService(port);
                        requestBatteryExemption();
                    });
                }
            } catch (Exception ignored) {
            }
            return raw;
        }

        @JavascriptInterface
        public String stopServer() {
            String raw = Rust.nativeStopServer();
            runOnUiThread(() -> {
                try {
                    stopService(new Intent(MainActivity.this, ServerService.class));
                } catch (Exception ignored) {
                }
            });
            return raw;
        }

        @JavascriptInterface
        public String serverStatus() {
            return Rust.nativeServerStatus();
        }

        @JavascriptInterface
        public String startTunnel(String mode, String token, String hostname, int port) {
            CloudflareTunnel current = CloudflareTunnel.get(MainActivity.this);
            current.attachWebView(web);
            int actualPort = port;
            try {
                JSONObject root = new JSONObject(Rust.nativeServerStatus());
                JSONObject data = root.optJSONObject("data");
                if (data != null && data.optBoolean("running") && data.optInt("port") > 0) {
                    actualPort = data.optInt("port");
                } else {
                    actualPort = 0;
                }
            } catch (Exception ignored) {
                actualPort = port;
            }
            String result = tunnelEnvelope(current.start(mode, token, hostname, actualPort));
            final int servicePort = actualPort;
            if (servicePort > 0) {
                // The tunnel and the server are both covered by ServerService.
                runOnUiThread(() -> startLocalService(servicePort));
            }
            return result;
        }

        @JavascriptInterface
        public String ensureService(int port) {
            runOnUiThread(() -> startLocalService(port));
            return "{\"ok\":true,\"data\":{}}";
        }

        @JavascriptInterface
        public String stopTunnel() {
            CloudflareTunnel.get(MainActivity.this).stop();
            return "{\"ok\":true,\"data\":{}}";
        }

        @JavascriptInterface
        public String tunnelStatus() {
            CloudflareTunnel current = CloudflareTunnel.get(MainActivity.this);
            current.attachWebView(web);
            return tunnelEnvelope(current.statusJson());
        }

        private String tunnelEnvelope(String payload) {
            try {
                return new JSONObject()
                        .put("ok", true)
                        .put("data", new JSONObject(payload))
                        .toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":{\"message\":\"tunnel status\"}}";
            }
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Не удалось открыть настройки",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void previewHtml(String html) {
            runOnUiThread(() -> showHtmlPreview(html == null ? "" : html));
        }

        @JavascriptInterface
        public void hideComposer() {
            runOnUiThread(() -> {
                if (composer != null) composer.hideImmediately();
            });
        }

        @JavascriptInterface
        public void hideKeyboard() {
            runOnUiThread(() -> {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                View focus = getCurrentFocus();
                if (focus != null) {
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                    }
                    focus.clearFocus();
                } else if (web != null && imm != null) {
                    imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                }
            });
        }

        @JavascriptInterface
        public String setComposerVisible(boolean visible) {
            runOnUiThread(() -> {
                if (composer != null) composer.setVisible(visible);
            });
            return "{}";
        }

        @JavascriptInterface
        public void setComposerAgent(boolean agentMode) {
            runOnUiThread(() -> {
                if (composer != null) composer.setAgentMode(agentMode);
            });
        }

        @JavascriptInterface
        public String setComposerState(boolean streaming, boolean online) {
            runOnUiThread(() -> {
                if (composer != null) composer.setState(streaming, online);
            });
            return "{}";
        }

        @JavascriptInterface
        public String setComposerTheme(boolean light) {
            runOnUiThread(() -> {
                if (composer != null) composer.setTheme(light);
            });
            return "{}";
        }

        @JavascriptInterface
        public String focusComposer() {
            runOnUiThread(() -> {
                if (composer != null) composer.focusInput();
            });
            return "{}";
        }

        @JavascriptInterface
        public String clearComposer() {
            runOnUiThread(() -> {
                if (composer != null) composer.clear();
            });
            return "{}";
        }

        @JavascriptInterface
        public void restoreComposerText(String text) {
            runOnUiThread(() -> {
                if (composer != null) composer.restoreDraft(text);
            });
        }

        @JavascriptInterface
        public String setBars(boolean light) {
            runOnUiThread(() -> {
                int background = Color.parseColor(light ? "#F9F9FF" : "#111318");
                Window window = getWindow();
                window.setStatusBarColor(background);
                window.setNavigationBarColor(background);
                if (root != null) {
                    root.setBackgroundColor(background);
                }
                if (composer != null) {
                    composer.setTheme(light);
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) {
                        int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                        controller.setSystemBarsAppearance(light ? mask : 0, mask);
                    }
                } else {
                    View decor = window.getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (light) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                    decor.setSystemUiVisibility(flags);
                }
            });
            return "{}";
        }

        @JavascriptInterface
        public String setTextZoom(int percent) {
            int clamped = Math.max(70, Math.min(200, percent));
            runOnUiThread(() -> {
                if (web != null) {
                    web.getSettings().setTextZoom(clamped);
                }
                if (composer != null) composer.setTextZoom(clamped);
            });
            return "{}";
        }

        @JavascriptInterface
        public String checkUpdate(boolean manual) {
            if (updater != null) updater.check(manual);
            return "{}";
        }

        @JavascriptInterface
        public String pendingUpdate() {
            return updater == null ? "" : updater.pending();
        }

        @JavascriptInterface
        public String downloadUpdate() {
            runOnUiThread(() -> {
                if (updater != null) updater.download();
            });
            return "{}";
        }

        @JavascriptInterface
        public String setUpdatePrefs(boolean online, boolean remind) {
            Updater.setPrefs(MainActivity.this, online, remind);
            return "{}";
        }

        @JavascriptInterface
        public String openUrl(String url) {
            runOnUiThread(() -> {
                try {
                    Uri uri = Uri.parse(url);
                    String scheme = uri.getScheme();
                    if (!"https".equals(scheme) && !"http".equals(scheme)) return;
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Не удалось открыть ссылку",
                            Toast.LENGTH_SHORT).show();
                }
            });
            return "{}";
        }

        @JavascriptInterface
        public String poll(long streamId) {
            return Rust.nativePoll(streamId);
        }

        @JavascriptInterface
        public String cancel(long streamId) {
            return Rust.nativeCancel(streamId);
        }

        @JavascriptInterface
        public String permissions() {
            JSONObject o = new JSONObject();
            try {
                o.put("notifications", notificationsGranted() ? "granted" : "missing");
                o.put("battery", ignoringBatteryOptimizations() ? "ignoring" : "optimized");
                o.put("sdk", Build.VERSION.SDK_INT);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public String requestNotifications() {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
            }
            return permissions();
        }

        @JavascriptInterface
        public String requestBattery() {
            runOnUiThread(() -> {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            });
            return permissions();
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(
                                "Local API DeepSeek", text == null ? "" : text));
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) {
                    return "";
                }
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    return "";
                }
                CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
                return text == null ? "" : text.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String appInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName);
                o.put("package", getPackageName());
            } catch (Exception ignored) {
            }
            return o.toString();
        }
    }

    /** Posts one notification when an answer finishes while the app is in the
     *  background. This is what the notification permission is actually for. */
    private void watch(String sendResult) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            return;
        }
        long streamId = 0;
        String title = "DeepSeek";
        try {
            JSONObject root = new JSONObject(sendResult);
            if (!root.optBoolean("ok")) {
                return;
            }
            JSONObject data = root.getJSONObject("data");
            streamId = data.getLong("stream_id");
            String t = data.getJSONObject("chat").optString("title", "");
            if (!t.isEmpty()) {
                title = t;
            }
        } catch (Exception e) {
            return;
        }
        final long id = streamId;
        final String label = title;
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 3600; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                String raw = Rust.nativePoll(id);
                JSONObject root;
                try {
                    root = new JSONObject(raw);
                } catch (Exception e) {
                    return;
                }
                if (!root.optBoolean("ok")) {
                    return;
                }
                JSONObject data = root.optJSONObject("data");
                if (data == null || !data.optBoolean("done")) {
                    continue;
                }
                if (!foreground && !data.optBoolean("cancelled")) {
                    notifyFinished(label);
                }
                return;
            }
        }, "deepseek-notify");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopLocalService() {
        runOnUiThread(() -> {
            try {
                stopService(new Intent(MainActivity.this, ServerService.class));
            } catch (Exception ignored) {
            }
        });
    }

    private void startLocalService(int port) {
        try {
            Intent intent = new Intent(this, ServerService.class).putExtra("port", port);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    /** Ask once when the local API is enabled; the onboarding already asks too. */
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyFinished(String title) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Ответы", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_stat_reply)
                    .setContentTitle("Ответ готов")
                    .setContentText(title)
                    .setContentIntent(pending)
                    .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private final class AssetsClient extends WebViewClient {
        private final Map<String, String> noStore =
                Collections.singletonMap("Cache-Control", "no-store");

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!HOST.equals(uri.getHost())) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }
            try {
                InputStream in = getAssets().open("web" + path);
                WebResourceResponse resp =
                        new WebResourceResponse(mime(path), "utf-8", in);
                resp.setResponseHeaders(noStore);
                return resp;
            } catch (IOException e) {
                return new WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        404,
                        "Not Found",
                        noStore,
                        new ByteArrayInputStream("not found".getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageReady = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            view.evaluateJavascript(
                    "window.__nativeComposerReady && window.__nativeComposerReady()", null);
            showPendingInstallError();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (HOST.equals(uri.getHost())) {
                return false;
            }
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
            }
            return true;
        }

        private String mime(String path) {
            String p = path.toLowerCase();
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js")) return "application/javascript";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".json")) return "application/json";
            if (p.endsWith(".woff2")) return "font/woff2";
            return "application/octet-stream";
        }
    }
}
