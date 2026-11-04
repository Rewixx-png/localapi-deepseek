package dev.localapi.deepseek;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * cloudflared on Android needs two things the official binary cannot do itself:
 * Java-side DNS and executable native libraries. Quick tunnels are registered
 * through the trycloudflare API here, and edge IPs are resolved with Android's
 * resolver; cloudflared then runs with explicit --edge addresses and never
 * touches /etc/resolv.conf (which does not exist on Android).
 *
 * Token (named) tunnels use the same edge-IP trick. The public hostname is
 * configured in the Cloudflare dashboard and passed in by the UI.
 */
final class CloudflareTunnel {
    private static final int EDGE_PORT = 7844;
    private static final int MAX_EDGES = 3;
    private static CloudflareTunnel instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<WebView> webView = new WeakReference<>(null);

    private Process process;
    private Thread reader;
    private File workDir;
    private volatile List<String> lastCommand;
    private volatile long launchSeq;
    private int restartAttempts;

    private volatile boolean stopped = true;
    private long generation;
    private volatile String state = "stopped";
    private volatile String mode = "quick";
    private volatile String publicUrl = "";
    private volatile String error = "";
    private volatile String lastLogError = "";
    private volatile int port;

    private CloudflareTunnel(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized CloudflareTunnel get(Context context) {
        if (instance == null) {
            instance = new CloudflareTunnel(context);
        }
        return instance;
    }

    static synchronized void shutdown() {
        CloudflareTunnel current = instance;
        instance = null;
        if (current != null) {
            current.stop();
        }
    }

    void attachWebView(WebView view) {
        webView = new WeakReference<>(view);
    }

    void detachWebView() {
        webView = new WeakReference<>(null);
    }

    synchronized String start(String requestedMode, String token, String hostname, int localPort) {
        stopInternal(false);
        long ticket = ++generation;
        mode = "token".equals(requestedMode) ? "token" : "quick";
        port = localPort;
        stopped = false;
        restartAttempts = 0;
        lastCommand = null;
        state = "starting";
        error = "";
        lastLogError = "";
        publicUrl = "";
        String cleanToken = token == null ? "" : token.trim();

        if (port <= 0) {
            fail("Сначала запусти локальный API");
            return statusJson();
        }
        if ("token".equals(mode)) {
            if (cleanToken.isEmpty()) {
                fail("Нужен токен именованного туннеля");
                return statusJson();
            }
            publicUrl = normalizeHost(hostname);
        }

        notifyStatus();
        String cleanHost = hostname == null ? "" : hostname.trim();
        Thread starter = new Thread(() -> startWorker(ticket, cleanToken, cleanHost), "cf-tunnel-start");
        starter.setDaemon(true);
        starter.start();
        return statusJson();
    }

    synchronized void stop() {
        stopInternal(true);
        notifyStatus();
    }

    synchronized String statusJson() {
        try {
            JSONObject out = new JSONObject();
            out.put("state", state);
            out.put("mode", mode);
            out.put("url", publicUrl);
            out.put("error", error);
            out.put("detail", lastLogError);
            out.put("port", port);
            return out.toString();
        } catch (Exception e) {
            return "{\"state\":\"error\",\"error\":\"status\"}";
        }
    }

    private void startWorker(long ticket, String token, String hostname) {
        try {
            File unixSocket = new File(context.getFilesDir(), "localapi.sock");
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(
                        unixSocket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                fail("Локальный API не слушает Unix-сокет");
                return;
            }
            List<String> edges = resolveEdges();
            if (stopped || ticket != generation) {
                return;
            }

            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) {
                fail("cloudflared не собран для этой архитектуры");
                return;
            }
            if (!binary.canExecute()) {
                fail("cloudflared не исполняемый (нужен extractNativeLibs)");
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(binary.getAbsolutePath());
            cmd.add("tunnel");
            cmd.add("--no-autoupdate");
            cmd.add("--no-prechecks");
            cmd.add("--retries");
            cmd.add("5");
            cmd.add("--edge-ip-version");
            cmd.add("4");
            for (String edge : edges) {
                cmd.add("--edge");
                cmd.add(edge + ":" + EDGE_PORT);
            }

            if ("quick".equals(mode)) {
                JSONObject registered = registerQuickTunnel();
                if (stopped || ticket != generation) {
                    return;
                }
                String id = registered.getString("id");
                String host = registered.getString("hostname");
                String account = registered.getString("account_tag");
                String secret = registered.getString("secret");
                publicUrl = "https://" + host;

                workDir = new File(context.getCacheDir(), "cf-" + id);
                if (!workDir.exists() && !workDir.mkdirs()) {
                    throw new IOException("не удалось создать каталог туннеля");
                }
                File creds = new File(workDir, "creds.json");
                File config = new File(workDir, "config.yml");
                writeFile(creds, new JSONObject()
                        .put("AccountTag", account)
                        .put("TunnelID", id)
                        .put("TunnelSecret", secret)
                        .toString());
                writeFile(config,
                        "tunnel: \"" + id + "\"\n" +
                        "credentials-file: \"" + creds.getAbsolutePath() + "\"\n" +
                        "protocol: http2\n" +
                        "ingress:\n" +
                        "  - hostname: \"" + host + "\"\n" +
                        "    service: \"unix:" + new File(context.getFilesDir(), "localapi.sock").getAbsolutePath() + "\"\n" +
                        "  - service: \"http_status:404\"\n");

                cmd.add("--config");
                cmd.add(config.getAbsolutePath());
                cmd.add("run");
                cmd.add(id);
            } else {
                cmd.add("--protocol");
                cmd.add("http2");
                cmd.add("run");
                cmd.add("--token");
                cmd.add(token);
            }

            if (stopped || ticket != generation) {
                return;
            }

            lastCommand = new ArrayList<>(cmd);
            launch(ticket, cmd);
        } catch (Exception e) {
            fail(message(e));
        }
    }

    private void launch(long ticket, List<String> cmd) {
        try {
            if (stopped || ticket != generation) {
                return;
            }
            File caBundle = ensureCaBundle();
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            builder.directory(context.getCacheDir());
            builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            builder.environment().put("NO_COLOR", "1");
            builder.environment().put("SSL_CERT_FILE", caBundle.getAbsolutePath());

            Process started = builder.start();
            long sequence = ++launchSeq;
            synchronized (this) {
                if (stopped || ticket != generation) {
                    started.destroy();
                    return;
                }
                process = started;
                state = "starting";
                error = "";
            }
            notifyStatus();

            reader = new Thread(() -> readLogs(started, ticket), "cf-tunnel-log");
            reader.setDaemon(true);
            reader.start();

            main.postDelayed(() -> {
                if (!stopped && ticket == generation && sequence == launchSeq
                        && !"connected".equals(state)) {
                    fail(lastLogError.isEmpty()
                            ? "Cloudflare не подключился за 60 секунд"
                            : lastLogError);
                }
            }, 60_000);
        } catch (Exception e) {
            fail(message(e));
        }
    }

    private List<String> resolveEdges() throws IOException {
        List<String> ips = new ArrayList<>();
        String[] hosts = {"region1.v2.argotunnel.com", "region2.v2.argotunnel.com"};
        IOException last = null;
        for (String host : hosts) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.getAddress().length == 4) {
                        String ip = address.getHostAddress();
                        if (!ips.contains(ip)) {
                            ips.add(ip);
                        }
                    }
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (ips.isEmpty()) {
            throw new IOException("DNS Cloudflare недоступен" +
                    (last == null ? "" : ": " + last.getMessage()));
        }
        return ips.subList(0, Math.min(MAX_EDGES, ips.size()));
    }

    private File ensureCaBundle() throws IOException {
        File target = new File(context.getFilesDir(), "cacert.pem");
        if (target.isFile() && target.length() > 1024) {
            return target;
        }
        try (InputStream input = context.getAssets().open("cacert.pem");
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return target;
    }

    private JSONObject registerQuickTunnel() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("https://api.trycloudflare.com/tunnel").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "localapi-deepseek/" +
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        connection.connect();

        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readAll(stream);
        if (code != 200) {
            throw new IOException("Cloudflare API " + code + ": " + shorten(body));
        }
        JSONObject result = new JSONObject(body).optJSONObject("result");
        if (result == null
                || result.optString("id").isEmpty()
                || result.optString("hostname").isEmpty()
                || result.optString("account_tag").isEmpty()
                || result.optString("secret").isEmpty()) {
            throw new IOException("Cloudflare API: неполный ответ");
        }
        return result;
    }

    private void readLogs(Process started, long ticket) {
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (stopped || ticket != generation) {
                    break;
                }
                String clean = line.replaceAll("\u001B\\[[;\\d]*m", "").trim();
                if (clean.isEmpty()) {
                    continue;
                }
                if (clean.contains("Registered tunnel connection")) {
                    synchronized (this) {
                        if (!stopped) {
                            state = "connected";
                            error = "";
                            lastLogError = "";
                            restartAttempts = 0;
                        }
                    }
                    notifyStatus();
                } else if (clean.contains("ERR") || clean.toLowerCase().contains("failed")) {
                    lastLogError = shorten(clean);
                }
            }
        } catch (IOException ignored) {
            // The process was stopped or the pipe closed.
        }

        int exit = -1;
        try {
            exit = started.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (started != process) {
                return;
            }
        }
        if (!stopped && ticket == generation) {
            List<String> command = lastCommand;
            if (command != null && restartAttempts < 4) {
                restartAttempts++;
                state = "starting";
                error = "";
                notifyStatus();
                int attempt = restartAttempts;
                main.postDelayed(() -> {
                    if (!stopped && ticket == generation && attempt == restartAttempts) {
                        launch(ticket, new ArrayList<>(command));
                    }
                }, 2500);
            } else {
                fail(lastLogError.isEmpty()
                        ? "cloudflared завершился (код " + exit + ")"
                        : lastLogError);
            }
        }
    }

    private synchronized void fail(String message) {
        if (stopped) {
            return;
        }
        stopped = true;
        state = "error";
        error = shorten(message);
        publicUrl = "";
        Process current = process;
        process = null;
        if (current != null) {
            current.destroy();
        }
        Thread log = reader;
        reader = null;
        if (log != null) {
            log.interrupt();
        }
        cleanupWorkDir();
        notifyStatus();
    }

    private void stopInternal(boolean clearStatus) {
        generation++;
        stopped = true;
        Process current = process;
        process = null;
        if (current != null) {
            current.destroy();
        }
        Thread log = reader;
        reader = null;
        if (log != null) {
            log.interrupt();
        }
        cleanupWorkDir();
        if (clearStatus) {
            state = "stopped";
            publicUrl = "";
            error = "";
            lastLogError = "";
        }
    }

    private void cleanupWorkDir() {
        File dir = workDir;
        workDir = null;
        if (dir != null) {
            deleteRecursive(dir);
        }
    }

    private void notifyStatus() {
        WebView view = webView.get();
        if (view == null) {
            return;
        }
        String payload = statusJson();
        main.post(() -> {
            try {
                view.evaluateJavascript("window.__tunnelStatus(" + JSONObject.quote(payload) + ")", null);
            } catch (Exception ignored) {
                // WebView is going away.
            }
        });
    }

    private void writeFile(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String normalizeHost(String hostname) {
        if (hostname == null) {
            return "";
        }
        String host = hostname.trim();
        if (host.isEmpty()) {
            return "";
        }
        if (host.startsWith("https://")) {
            host = host.substring(8);
        } else if (host.startsWith("http://")) {
            host = host.substring(7);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.isEmpty() ? "" : "https://" + host;
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        return clean.length() <= 220 ? clean : clean.substring(0, 220) + "…";
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
