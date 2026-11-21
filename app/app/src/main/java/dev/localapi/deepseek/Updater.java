package dev.localapi.deepseek;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
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
 * from an allowlisted HTTPS host with progress, strictly verifies its SHA-256
 * (fail-closed), and hands it to the system PackageInstaller.
 */
final class Updater {
    static final String MANIFEST_URL = AppConfig.UPDATE_MANIFEST_URL;
    static final String MANIFEST_FALLBACK = AppConfig.UPDATE_MANIFEST_FALLBACK;
    static final String SOURCE_URL = AppConfig.REPO_URL;
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
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_APK_BYTES = 250L * 1024 * 1024;

    interface Listener {
        void onUpdateAvailable(String manifestJson, boolean manual);
        void onUpdateNone(boolean manual);
        void onUpdateError(String message);
        void onUpdateProgress(int percent);
        void onUpdateStatus(String state);
        void onShowPendingUpdate();
    }

    private final Activity activity;
    private volatile Listener listener;
    private volatile boolean downloading;
    private boolean pendingDownload;
    private File pendingInstall;

    static String installErrorText(int status, String message) {
        String base;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                base = "Установка отменена";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                base = "Установка заблокирована системой (разрешите неизвестные источники)";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                base = "Конфликт пакета: APK подписан другим ключом или конфликтует "
                        + "с установленной версией";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                base = "APK несовместим с этим устройством";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                base = "APK повреждён или недействителен";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                base = "Недостаточно места для установки";
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                base = "Система запросила подтверждение установки, но окно не открылось";
                break;
            default:
                base = "Ошибка установки";
                break;
        }
        StringBuilder text = new StringBuilder(base)
                .append(" (код ").append(status).append(")");
        if (message != null && !message.trim().isEmpty()) {
            text.append(": ").append(message.trim());
        }
        return text.toString();
    }

    Updater(Activity activity) {
        this.activity = activity;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void ui(Runnable r) {
        activity.runOnUiThread(r);
    }

    void onLaunch() {
        scheduleJob(activity);
        if (pending().isEmpty()) cleanupApks(activity);
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
            ui(() -> { Listener l = listener; if (l != null) l.onShowPendingUpdate(); });
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
        String raw = pendingJson(activity);
        if (raw.isEmpty()) return "";
        try {
            JSONObject manifest = new JSONObject(raw);
            if (manifest.optInt("versionCode", 0) <= versionCode(activity)) return "";
            return raw;
        } catch (Exception e) {
            return "";
        }
    }

    void check(final boolean manual) {
        if (!online(activity)) {
            if (manual) {
                ui(() -> { Listener l = listener; if (l != null) l.onUpdateError("Нет доступа к интернету"); });
            }
            return;
        }
        new Thread(() -> {
            try {
                JSONObject manifest = fetchManifest();
                int latest = manifest.optInt("versionCode", 0);
                if (latest > versionCode(activity)) {
                    storePending(activity, manifest.toString());
                    final String mStr = manifest.toString();
                    ui(() -> { Listener l = listener; if (l != null) l.onUpdateAvailable(mStr, manual); });
                } else {
                    clearPending(activity);
                    cleanupApks(activity);
                    ui(() -> { Listener l = listener; if (l != null) l.onUpdateNone(manual); });
                }
            } catch (Exception e) {
                if (manual) {
                    ui(() -> { Listener l = listener; if (l != null) l.onUpdateError("Не удалось проверить обновления"); });
                }
            }
        }, "update-check").start();
    }

    void download() {
        if (downloading) return;
        if (!online(activity)) {
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateError("Нет доступа к интернету"); });
            return;
        }
        if (!canInstall(activity)) {
            pendingDownload = true;
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateStatus("permission"); });
            openInstallSettings();
            return;
        }
        JSONObject manifest;
        try {
            manifest = new JSONObject(pendingJson(activity));
        } catch (Exception e) {
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateError("Нет данных об обновлении"); });
            return;
        }
        downloading = true;
        new Thread(() -> downloadAndInstall(manifest), "update-download").start();
    }

    static boolean isValidSha256(String hex) {
        return hex != null && hex.trim().matches("(?i)[0-9a-f]{64}");
    }

    static boolean isAllowedDownloadUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return false;
        try {
            URL url = new URL(rawUrl.trim());
            return "https".equalsIgnoreCase(url.getProtocol())
                    && AppConfig.isAllowedUpdateHost(url.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private void downloadAndInstall(JSONObject manifest) {
        File part = new File(activity.getCacheDir(), "update.apk.part");
        File apk = new File(activity.getCacheDir(), "update.apk");
        HttpURLConnection connection = null;
        try {
            String raw = manifest.optString("url", "");
            if (!isAllowedDownloadUrl(raw)) {
                throw new IOException("недоверенный адрес обновления");
            }
            String expected = manifest.optString("sha256", "").trim();
            if (!isValidSha256(expected)) {
                throw new IOException("в манифесте отсутствует корректная контрольная сумма SHA-256");
            }
            if (part.exists() && !part.delete()) throw new IOException("кэш занят");
            connection = openDownloadVerified(raw);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES) throw new IOException("APK превышает допустимый размер");
            long read = 0;
            int lastPercent = -1;
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateProgress(0); });
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    read += count;
                    if (read > MAX_APK_BYTES) throw new IOException("превышен лимит размера APK");
                    out.write(buffer, 0, count);
                    if (total > 0) {
                        int percent = (int) Math.min(100, read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int pct = percent;
                            ui(() -> { Listener l = listener; if (l != null) l.onUpdateProgress(pct); });
                        }
                    }
                }
                out.flush();
            }
            if (!expected.equalsIgnoreCase(sha256(part))) {
                throw new IOException("контрольная сумма не совпала");
            }
            if (apk.exists() && !apk.delete()) throw new IOException("кэш занят");
            if (!part.renameTo(apk)) throw new IOException("не удалось сохранить APK");
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateProgress(100); });
            install(apk);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = "ошибка сети";
            final String dlErr = "Не удалось скачать обновление: " + message;
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateError(dlErr); });
        } finally {
            if (connection != null) connection.disconnect();
            downloading = false;
        }
    }

    private static HttpURLConnection openDownloadVerified(String initialUrl) throws IOException {
        String current = initialUrl;
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            if (!isAllowedDownloadUrl(current)) {
                throw new IOException("редирект на недоверенный хост");
            }
            HttpURLConnection conn = open(current, "application/octet-stream", false);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("пустой редирект");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("слишком много редиректов");
    }

    private void install(File apk) {
        if (!apk.exists()) {
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateError("Файл обновления не найден"); });
            return;
        }
        if (!canInstall(activity)) {
            pendingInstall = apk;
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateStatus("permission"); });
            openInstallSettings();
            return;
        }
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            int sessionId = installer.createSession(params);
            boolean committed = false;
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
                Intent result = new Intent(activity, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("install_result", true);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getActivity(activity, 1, result, flags);
                session.commit(pending.getIntentSender());
                committed = true;
            } finally {
                if (!committed) {
                    try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
                }
            }
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateStatus("installing"); });
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = "установка недоступна";
            final String instErr = "Не удалось установить обновление: " + message;
            ui(() -> { Listener l = listener; if (l != null) l.onUpdateError(instErr); });
        }
    }

    private void openInstallSettings() {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
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

    // ── static helpers (also used by UpdateJobService) ───────────────────

    static JSONObject fetchManifest() throws Exception {
        try {
            return readManifest(MANIFEST_URL, "application/vnd.github.raw+json");
        } catch (Exception primary) {
            return readManifest(MANIFEST_FALLBACK, "application/json");
        }
    }

    private static JSONObject readManifest(String url, String accept) throws Exception {
        HttpURLConnection connection = open(url, accept, true);
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
                        || !isAllowedDownloadUrl(manifest.optString("url", ""))
                        || !isValidSha256(manifest.optString("sha256", ""))) {
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

    @SuppressWarnings("deprecation")
    static int versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
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

    static void clearPending(Context context) {
        prefs(context).edit().remove(KEY_PENDING).apply();
    }

    private static void cleanupApks(Context context) {
        if (context == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(context.getCacheDir(), "update.apk").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(context.getCacheDir(), "update.apk.part").delete();
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

    private static HttpURLConnection open(String url, String accept, boolean followRedirects)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setRequestProperty("User-Agent", "LocalApiDeepSeek");
        connection.setRequestProperty("Accept", accept);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Pragma", "no-cache");
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
