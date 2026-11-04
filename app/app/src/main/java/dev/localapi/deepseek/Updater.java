package dev.localapi.deepseek;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * In-app update flow: reads update.json from the repository, downloads the APK
 * with progress and hands it to the system installer. The manifest is public,
 * the APK is verified against its SHA-256 before installation.
 */
final class Updater {
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/Rewixx-png/localapi-deepseek/main/update.json";
    static final String SOURCE_URL = "https://github.com/Rewixx-png/localapi-deepseek";
    static final String ACTION_INSTALL_RESULT = "dev.localapi.deepseek.INSTALL_RESULT";
    static final int JOB_ID = 9701;

    private static final String PREFS = "updater";
    private static final String KEY_PENDING = "pending_update";
    private static final String KEY_REMIND = "remind";
    private static final String KEY_ONLINE = "online";
    private static final String KEY_INSTALL_ASKED = "install_asked";
    private static final String CHANNEL_ID = "updates";
    private static final int NOTIFICATION_ID = 42;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final Activity activity;
    private final WebView web;
    private volatile boolean downloading;
    private boolean pendingDownload;
    private File pendingInstall;

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                runJs("window.__updateStatus && window.__updateStatus('installed')");
                return;
            }
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (message == null || message.isEmpty()) message = "Установка отменена";
            runJs("window.__updateError && window.__updateError("
                    + JSONObject.quote(message) + ")");
        }
    };

    Updater(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    void onLaunch() {
        scheduleJob(activity);
        try {
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(installReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(installReceiver, filter);
            }
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = prefs(activity);
        if (Build.VERSION.SDK_INT >= 26 && !prefs.getBoolean(KEY_INSTALL_ASKED, false)) {
            prefs.edit().putBoolean(KEY_INSTALL_ASKED, true).apply();
            if (!canInstall(activity)) {
                activity.getWindow().getDecorView().postDelayed(this::openInstallSettings, 800);
            }
        }
    }

    void onResume() {
        Intent intent = activity.getIntent();
        if (intent != null && intent.getBooleanExtra("update_notification", false)) {
            intent.removeExtra("update_notification");
            runJs("window.__showPendingUpdate && window.__showPendingUpdate()");
        }
        if (canInstall(activity)) {
            if (pendingDownload) {
                pendingDownload = false;
                download();
            } else if (pendingInstall != null) {
                File file = pendingInstall;
                pendingInstall = null;
                install(file);
            }
        }
    }

    String pending() {
        return pendingJson(activity);
    }

    void check(final boolean manual) {
        new Thread(() -> {
            try {
                JSONObject manifest = fetchManifest();
                int latest = manifest.optInt("versionCode", 0);
                if (latest > versionCode(activity)) {
                    storePending(activity, manifest.toString());
                    runJs("window.__updateAvailable && window.__updateAvailable("
                            + JSONObject.quote(manifest.toString()) + "," + manual + ")");
                } else {
                    runJs("window.__updateNone && window.__updateNone(" + manual + ")");
                }
            } catch (Exception e) {
                if (manual) {
                    runJs("window.__updateError && window.__updateError("
                            + JSONObject.quote("Не удалось проверить обновления") + ")");
                }
            }
        }, "update-check").start();
    }

    void download() {
        if (downloading) return;
        if (!canInstall(activity)) {
            pendingDownload = true;
            runJs("window.__updateStatus && window.__updateStatus('permission')");
            openInstallSettings();
            return;
        }
        JSONObject manifest;
        try {
            manifest = new JSONObject(pendingJson(activity));
        } catch (Exception e) {
            runJs("window.__updateError && window.__updateError("
                    + JSONObject.quote("Нет данных об обновлении") + ")");
            return;
        }
        downloading = true;
        new Thread(() -> downloadAndInstall(manifest), "update-download").start();
    }

    private void downloadAndInstall(JSONObject manifest) {
        File part = new File(activity.getCacheDir(), "update.apk.part");
        File apk = new File(activity.getCacheDir(), "update.apk");
        HttpURLConnection connection = null;
        try {
            String raw = manifest.optString("url", "");
            Uri uri = Uri.parse(raw);
            if (!"https".equals(uri.getScheme())) {
                throw new IOException("небезопасный адрес обновления");
            }
            if (part.exists() && !part.delete()) throw new IOException("кэш занят");
            connection = open(raw, "application/octet-stream");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long total = connection.getContentLengthLong();
            long read = 0;
            int lastPercent = -1;
            runJs("window.__updateProgress && window.__updateProgress(0)");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                    read += count;
                    if (total > 0) {
                        int percent = (int) Math.min(100, read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            runJs("window.__updateProgress && window.__updateProgress("
                                    + percent + ")");
                        }
                    }
                }
                out.flush();
            }
            String expected = manifest.optString("sha256", "");
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(sha256(part))) {
                throw new IOException("контрольная сумма не совпала");
            }
            if (apk.exists() && !apk.delete()) throw new IOException("кэш занят");
            if (!part.renameTo(apk)) throw new IOException("не удалось сохранить APK");
            runJs("window.__updateProgress && window.__updateProgress(100)");
            install(apk);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = "ошибка сети";
            runJs("window.__updateError && window.__updateError("
                    + JSONObject.quote("Не удалось скачать обновление: " + message) + ")");
        } finally {
            if (connection != null) connection.disconnect();
            downloading = false;
        }
    }

    private void install(File apk) {
        if (!apk.exists()) {
            runJs("window.__updateError && window.__updateError("
                    + JSONObject.quote("Файл обновления не найден") + ")");
            return;
        }
        if (!canInstall(activity)) {
            pendingInstall = apk;
            runJs("window.__updateStatus && window.__updateStatus('permission')");
            openInstallSettings();
            return;
        }
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) > 0) {
                        out.write(buffer, 0, count);
                    }
                    session.fsync(out);
                }
                Intent result = new Intent(ACTION_INSTALL_RESULT)
                        .setPackage(activity.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(activity, 0, result, flags);
                session.commit(pending.getIntentSender());
            }
            runJs("window.__updateStatus && window.__updateStatus('installing')");
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = "установка недоступна";
            runJs("window.__updateError && window.__updateError("
                    + JSONObject.quote("Не удалось установить обновление: " + message) + ")");
        }
    }

    private void openInstallSettings() {
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, "Разрешите установку из неизвестных источников",
                    Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                } catch (Exception ignored) {
                }
            }
        });
    }

    void onDestroy() {
        try {
            activity.unregisterReceiver(installReceiver);
        } catch (Exception ignored) {
        }
    }

    private void runJs(String script) {
        if (web == null) return;
        web.post(() -> {
            try {
                web.evaluateJavascript(script, null);
            } catch (Exception ignored) {
            }
        });
    }

    // ── static helpers (also used by UpdateJobService) ───────────────────

    static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = open(MANIFEST_URL, "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8 * 1024];
                int count;
                int total = 0;
                while ((count = in.read(buffer)) > 0) {
                    total += count;
                    if (total > 64 * 1024) throw new IOException("манифест слишком большой");
                    out.write(buffer, 0, count);
                }
                JSONObject manifest = new JSONObject(
                        new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (manifest.optInt("versionCode", 0) <= 0
                        || manifest.optString("url", "").isEmpty()) {
                    throw new IOException("некорректный манифест");
                }
                return manifest;
            }
        } finally {
            connection.disconnect();
        }
    }

    static boolean isNewer(Context context, JSONObject manifest) {
        return manifest.optInt("versionCode", 0) > versionCode(context);
    }

    static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String pendingJson(Context context) {
        return prefs(context).getString(KEY_PENDING, "");
    }

    static void storePending(Context context, String json) {
        prefs(context).edit().putString(KEY_PENDING, json).apply();
    }

    static void setPrefs(Context context, boolean online, boolean remind) {
        prefs(context).edit()
                .putBoolean(KEY_ONLINE, online)
                .putBoolean(KEY_REMIND, remind)
                .apply();
    }

    static boolean remindersEnabled(Context context) {
        return prefs(context).getBoolean(KEY_REMIND, true);
    }

    static boolean online(Context context) {
        return prefs(context).getBoolean(KEY_ONLINE, true);
    }

    static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    static void scheduleJob(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler)
                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(job);
        } catch (Exception ignored) {
        }
    }

    static void notifyUpdate(Context context, JSONObject manifest) {
        if (!remindersEnabled(context)) return;
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_DEFAULT);
                manager.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("update_notification", true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getActivity(context, 0, open, flags);
            String version = manifest.optString("versionName", "");
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_stat_reply)
                    .setContentTitle("Доступно обновление" +
                            (version.isEmpty() ? "" : " " + version))
                    .setContentText("Откройте приложение, чтобы скачать и установить")
                    .setContentIntent(pending)
                    .setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LocalApiDeepSeek");
        connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
