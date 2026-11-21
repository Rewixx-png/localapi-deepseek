package dev.localapi.deepseek;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * cloudflared on Android needs Java-side DNS and executable native libraries.
 * Quick tunnels are registered through the trycloudflare API here, edge IPs
 * are resolved via Android's resolver, and traffic enters the local API over
 * a Unix domain socket to bypass VPN interception on loopback TCP.
 */
final class CloudflareTunnel {
    private static final int EDGE_PORT = 7844;
    private static final int MAX_EDGES = 3;
    private static CloudflareTunnel instance;

    static final class Status {
        final String state;
        final String mode;
        final String url;
        final String error;
        final String detail;
        final int port;

        Status(String state, String mode, String url, String error, String detail, int port) {
            this.state = state;
            this.mode = mode;
            this.url = url;
            this.error = error;
            this.detail = detail;
            this.port = port;
        }
    }

    interface Listener {
        void onTunnelStatus(Status status);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    private Process process;
    private Thread reader;
    private File workDir;
    private volatile List<String> lastCommand;
    private volatile long launchSeq;
    private int restartAttempts;

    private volatile boolean online = true;
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

    void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized void setOnline(boolean enabled) {
        this.online = enabled;
        if (!enabled) {
            stop();
        }
    }

    synchronized Status start(String requestedMode, String token, String hostname, int localPort) {
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

        if (!online) {
            fail(ticket, "Доступ к интернету выключен");
            return status();
        }
        if (port <= 0) {
            fail(ticket, "Сначала запусти локальный API");
            return status();
        }
        if ("token".equals(mode)) {
            if (cleanToken.isEmpty()) {
                fail(ticket, "Нужен токен именованного туннеля");
                return status();
            }
            publicUrl = normalizeHost(hostname);
        }

        notifyStatus();
        String cleanHost = hostname == null ? "" : hostname.trim();
        Thread starter = new Thread(() -> startWorker(ticket, cleanToken, cleanHost), "cf-tunnel-start");
        starter.setDaemon(true);
        starter.start();
        return status();
    }

    synchronized void stop() {
        stopInternal(true);
        notifyStatus();
    }

    synchronized Status status() {
        return new Status(state, mode, publicUrl, error, lastLogError, port);
    }

    private void startWorker(long ticket, String token, String hostname) {
        File localWorkDir = null;
        try {
            File unixSocket = new File(context.getFilesDir(), "localapi.sock");
            try (LocalSocket socket = new LocalSocket()) {
                socket.setSoTimeout(3000);
                socket.connect(new LocalSocketAddress(
                        unixSocket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                fail(ticket, "Локальный API не слушает Unix-сокет");
                return;
            }
            List<String> edges = resolveEdges();
            if (isStale(ticket)) return;

            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) {
                fail(ticket, "cloudflared не собран для этой архитектуры");
                return;
            }
            if (!binary.canExecute()) {
                fail(ticket, "cloudflared не исполняемый (нужен extractNativeLibs)");
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
                if (isStale(ticket)) return;

                String id = registered.getString("id");
                String host = registered.getString("hostname");
                String account = registered.getString("account_tag");
                String secret = registered.getString("secret");

                localWorkDir = new File(context.getCacheDir(), "cf-" + id);
                if (!localWorkDir.exists() && !localWorkDir.mkdirs()) {
                    throw new IOException("не удалось создать каталог туннеля");
                }
                File creds = new File(localWorkDir, "creds.json");
                File config = new File(localWorkDir, "config.yml");
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
                        "    service: \"unix:" + unixSocket.getAbsolutePath() + "\"\n" +
                        "  - service: \"http_status:404\"\n");

                synchronized (this) {
                    if (stopped || !online || ticket != generation) {
                        deleteRecursive(localWorkDir);
                        return;
                    }
                    publicUrl = "https://" + host;
                    workDir = localWorkDir;
                }

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

            if (isStale(ticket)) {
                deleteRecursive(localWorkDir);
                return;
            }

            lastCommand = new ArrayList<>(cmd);
            launch(ticket, cmd);
        } catch (Exception e) {
            deleteRecursive(localWorkDir);
            fail(ticket, message(e));
        }
    }

    private void launch(long ticket, List<String> cmd) {
        try {
            if (isStale(ticket)) return;
            File caBundle = ensureCaBundle();
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            builder.directory(context.getCacheDir());
            builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            builder.environment().put("NO_COLOR", "1");
            builder.environment().put("SSL_CERT_FILE", caBundle.getAbsolutePath());

            Process started = builder.start();
            long sequence;
            synchronized (this) {
                if (stopped || !online || ticket != generation) {
                    started.destroy();
                    return;
                }
                sequence = ++launchSeq;
                process = started;
                state = "starting";
                error = "";
            }
            notifyStatus();

            Thread logThread = new Thread(() -> readLogs(started, ticket), "cf-tunnel-log");
            logThread.setDaemon(true);
            synchronized (this) {
                reader = logThread;
            }
            logThread.start();

            main.postDelayed(() -> {
                synchronized (CloudflareTunnel.this) {
                    if (stopped || ticket != generation || sequence != launchSeq
                            || "connected".equals(state)) {
                        return;
                    }
                }
                fail(ticket, lastLogError.isEmpty()
                        ? "Cloudflare не подключился за 60 секунд"
                        : lastLogError);
            }, 60_000);
        } catch (Exception e) {
            fail(ticket, message(e));
        }
    }

    private synchronized boolean isStale(long ticket) {
        return stopped || !online || ticket != generation;
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
        try {
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
        } finally {
            connection.disconnect();
        }
    }

    private void readLogs(Process started, long ticket) {
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (isStale(ticket)) break;
                String clean = line.replaceAll("\u001B\\[[;\\d]*m", "").trim();
                if (clean.isEmpty()) continue;

                if (clean.contains("Registered tunnel connection")) {
                    synchronized (this) {
                        if (!stopped && ticket == generation) {
                            state = "connected";
                            error = "";
                            lastLogError = "";
                            restartAttempts = 0;
                        }
                    }
                    notifyStatus();
                } else {
                    String lower = clean.toLowerCase();
                    if (clean.contains(" ERR ") || clean.contains("ERR\t")
                            || lower.contains("error=") || lower.contains("failed")) {
                        lastLogError = shorten(clean);
                    }
                }
            }
        } catch (IOException ignored) {
        }

        int exit = -1;
        try {
            exit = started.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        boolean shouldRestart = false;
        int attempt = 0;
        List<String> command = null;
        synchronized (this) {
            if (started != process || stopped || !online || ticket != generation) {
                return;
            }
            command = lastCommand;
            if (command != null && restartAttempts < 4) {
                restartAttempts++;
                attempt = restartAttempts;
                state = "starting";
                error = "";
                shouldRestart = true;
            }
        }

        if (shouldRestart) {
            notifyStatus();
            final int targetAttempt = attempt;
            final List<String> restartCmd = new ArrayList<>(command);
            main.postDelayed(() -> {
                synchronized (CloudflareTunnel.this) {
                    if (stopped || !online || ticket != generation || targetAttempt != restartAttempts) {
                        return;
                    }
                }
                launch(ticket, restartCmd);
            }, 2500);
        } else {
            fail(ticket, lastLogError.isEmpty()
                    ? "cloudflared завершился (код " + exit + ")"
                    : lastLogError);
        }
    }

    private synchronized void fail(long ticket, String message) {
        if (stopped || ticket != generation) {
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
        Status snapshot = status();
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onTunnelStatus(snapshot));
        }
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
