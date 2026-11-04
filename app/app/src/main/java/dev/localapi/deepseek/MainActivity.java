package dev.localapi.deepseek;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

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

    private static final String CHANNEL_ID = "replies";
    private static final int NOTIFICATION_ID = 41;

    private WebView web;
    private LinearLayout root;
    private NativeComposer composer;
    private Updater updater;
    private CloudflareTunnel tunnel;
    private volatile boolean foreground = false;

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
        tunnel = CloudflareTunnel.get(this);
        tunnel.attachWebView(web);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111318"));

        // Android 15 draws apps edge-to-edge when targetSdk >= 35. Opt into that
        // explicitly on API 30+ and own the insets ourselves: adjustResize is a
        // no-op under edge-to-edge, so the keyboard would otherwise cover the
        // composer and disturb the WebView's IME session.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

            // Resizing the content while the IME animates resets the soft
            // keyboard's composition and drops characters. Keep the layout
            // still during the animation and apply the final IME padding once
            // it has finished.
            final int[] target = new int[4];
            final boolean[] animating = {false};
            final Runnable applyPadding = () -> root.setPadding(
                    target[0], target[1], target[2], target[3]);
            final Runnable animationGuard = () -> {
                // On API 30 a cancelled animation may never dispatch onEnd.
                animating[0] = false;
                applyPadding.run();
            };

            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                target[0] = bars.left;
                target[1] = bars.top;
                target[2] = bars.right;
                target[3] = Math.max(bars.bottom, ime.bottom);
                if (!animating[0]) applyPadding.run();
                return insets;
            });

            root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override
                public void onPrepare(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                    animating[0] = true;
                    root.removeCallbacks(animationGuard);
                    root.postDelayed(animationGuard, 1000);
                }

                @Override
                public WindowInsets onProgress(WindowInsets insets,
                        List<WindowInsetsAnimation> runningAnimations) {
                    return insets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                    root.removeCallbacks(animationGuard);
                    animating[0] = false;
                    applyPadding.run();
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
        if (updater != null) {
            updater.onResume();
        }
    }

    @Override
    protected void onPause() {
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
        if (updater != null) {
            updater.onDestroy();
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
        public String listChats() {
            return Rust.nativeListChats();
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
        public String send(String chatId, String message, String model, String effort, boolean thinking, boolean search, String imagesJson) {
            String raw = Rust.nativeSend(chatId, message, model, effort, thinking, search, imagesJson);
            watch(raw);
            return raw;
        }

        @JavascriptInterface
        public String startServer(int port) {
            String raw = Rust.nativeStartServer(port);
            try {
                if (new JSONObject(raw).optBoolean("ok")) {
                    runOnUiThread(() -> startLocalService(port));
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
            return tunnelEnvelope(current.start(mode, token, hostname, actualPort));
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
        public String setComposerVisible(boolean visible) {
            runOnUiThread(() -> {
                if (composer != null) composer.setVisible(visible);
            });
            return "{}";
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
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(
                    "window.__nativeComposerReady && window.__nativeComposerReady()", null);
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
