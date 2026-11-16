package dev.localapi.deepseek;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the Ubuntu Base/QEMU guest assets and launches QEMU with the
 * app-private agent workspace shared over 9p.
 *
 * QEMU itself is packaged in jniLibs (arm64-v8a). There is no KVM on Android,
 * so the guest runs under TCG and is slow.
 */
final class SandboxManager {
    static final String VERSION = "ubuntu-disk-1";
    static final String ASSET_URL =
            "https://github.com/Rewixx-png/localapi-deepseek/releases/download/"
                    + "sandbox-ubuntu-disk-1/ubuntu-sandbox.zip";
    static final String ASSET_SHA =
            "22d2c6d4feba7d514e1406ce3ad394daa61b98fcd72e47fee01650c16f0775b6";
    static final long ASSET_SIZE = 132758428L;

    private static final String NET_READY_MARKER = "__LOCALAPI_NET_READY__";
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static final Pattern ANSI_PATTERN =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private final Activity activity;
    private final WebView web;
    private volatile Process process;
    private volatile boolean installing;
    private volatile boolean running;
    private volatile boolean networkConfigured;
    private volatile boolean shellReady;
    private volatile String activeChatId = "";

    private final Object startLock = new Object();
    private final Object serialLock = new Object();
    private final StringBuilder serial = new StringBuilder();
    private final Object execLock = new Object();
    private final Object writeLock = new Object();

    private static final String PREFS = "sandbox_config";
    private static final String KEY_RAM = "ram_mb";
    private static final String KEY_CORES = "cores";
    private int ramMb = 2048;
    private int cores = 2;

    SandboxManager(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "sandbox");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Не удалось создать папку песочницы");
        }
        return dir;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int maxRamMb() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(
                    Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long total = info.totalMem / (1024L * 1024L);
            if (total < 1024) total = 1024;
            return (int) Math.min(6144L, Math.max(512L, total - 1024L));
        } catch (Exception e) {
            return 2048;
        }
    }

    private int maxCores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private void loadConfig() {
        SharedPreferences prefs = prefs(activity);
        ramMb = prefs.getInt(KEY_RAM, Math.min(2048, maxRamMb()));
        cores = prefs.getInt(KEY_CORES, Math.min(2, maxCores()));
        ramMb = Math.max(512, Math.min(maxRamMb(), ramMb));
        cores = Math.max(1, Math.min(maxCores(), cores));
    }

    JSONObject setConfig(int ramMb, int cores) throws Exception {
        this.ramMb = Math.max(512, Math.min(maxRamMb(), ramMb));
        this.cores = Math.max(1, Math.min(maxCores(), cores));
        prefs(activity).edit()
                .putInt(KEY_RAM, this.ramMb)
                .putInt(KEY_CORES, this.cores)
                .apply();
        return status();
    }

    static boolean installed(Context context) {
        File dir = dir(context);
        return new File(dir, "vmlinuz").isFile()
                && new File(dir, "rootfs.img").isFile()
                && new File(dir, "qemu-data/efi-virtio.rom").isFile();
    }

    JSONObject status() throws Exception {
        JSONObject out = new JSONObject();
        loadConfig();
        out.put("installed", installed(activity));
        out.put("installing", installing);
        out.put("running", running);
        out.put("version", VERSION);
        out.put("size", ASSET_SIZE);
        out.put("ramMb", ramMb);
        out.put("cores", cores);
        out.put("maxRamMb", maxRamMb());
        out.put("maxCores", maxCores());
        out.put("abi", android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "");
        File log = new File(activity.getCacheDir(), "sandbox.log");
        out.put("log", tail(log, 64 * 1024));
        return out;
    }

    void install() {
        if (installing) return;
        if (installed(activity)) {
            runJs("window.__sandboxInstalled && window.__sandboxInstalled()");
            return;
        }
        installing = true;
        new Thread(() -> {
            File zip = new File(activity.getCacheDir(), "sandbox-assets.zip");
            try {
                download(zip);
                String actual = sha256(zip);
                if (!ASSET_SHA.equalsIgnoreCase(actual)) {
                    throw new IOException("Контрольная сумма песочницы не совпала");
                }
                unzip(zip, dir(activity));
                installing = false;
                runJs("window.__sandboxInstalled && window.__sandboxInstalled()");
                log("sandbox installed " + VERSION);
            } catch (Exception e) {
                installing = false;
                String message = e.getMessage() == null
                        ? "Не удалось установить песочницу"
                        : e.getMessage();
                log("sandbox install error: " + message);
                runJs("window.__sandboxError && window.__sandboxError("
                        + JSONObject.quote(message) + ")");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
            }
        }, "sandbox-install").start();
    }

    void start(String chatId) {
        if (!installed(activity)) {
            runJs("window.__sandboxError && window.__sandboxError("
                    + JSONObject.quote("Сначала скачайте песочницу") + ")");
            return;
        }
        final Process started;
        final String currentChat;
        synchronized (startLock) {
            if (running) {
                runJs("window.__sandboxOutput && window.__sandboxOutput("
                        + JSONObject.quote("[sandbox уже запущена]") + ")");
                return;
            }
            try {
                loadConfig();
                currentChat = chatId == null ? "" : chatId;
                activeChatId = currentChat;
                File dir = dir(activity);
                String libs = activity.getApplicationInfo().nativeLibraryDir;
                File qemu = new File(libs, "libqemu-system-aarch64.so");
                if (!qemu.isFile()) {
                    throw new IOException("QEMU не упакован в APK для этой ABI");
                }
                File workspace = AgentWorkspace.root(activity, currentChat);
                File rootfs = new File(dir, "rootfs.img");
                List<String> args = new ArrayList<>();
                args.add("/system/bin/linker64");
                args.add(qemu.getAbsolutePath());
                Collections.addAll(args,
                        "-M", "virt",
                        "-cpu", "max",
                        "-m", String.valueOf(ramMb),
                        "-smp", String.valueOf(cores),
                        "-nographic",
                        "-no-reboot",
                        "-L", new File(dir, "qemu-data").getAbsolutePath(),
                        "-kernel", new File(dir, "vmlinuz").getAbsolutePath());
                if (rootfs.isFile()) {
                    args.add("-drive");
                    args.add("file=" + rootfs.getAbsolutePath()
                            + ",if=virtio,format=raw,cache=writeback");
                    args.add("-append");
                    args.add("root=/dev/vda rw console=ttyAMA0 init=/init loglevel=7");
                } else {
                    args.add("-initrd");
                    args.add(new File(dir, "initramfs.cpio.gz").getAbsolutePath());
                    args.add("-append");
                    args.add("console=ttyAMA0 rdinit=/init loglevel=7");
                }
                Collections.addAll(args,
                        "-virtfs", "local,path=" + workspace.getAbsolutePath()
                                + ",mount_tag=workspace,security_model=none",
                        "-netdev", "user,id=net0",
                        "-device", "virtio-net-device,netdev=net0");
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);
                pb.directory(activity.getCacheDir());
                pb.environment().put("LD_LIBRARY_PATH", libs);
                pb.environment().put("HOME", dir.getAbsolutePath());
                pb.environment().put("TMPDIR", activity.getCacheDir().getAbsolutePath());

                started = pb.start();
                process = started;
                running = true;
                networkConfigured = false;
                shellReady = false;
                synchronized (serialLock) {
                    serial.setLength(0);
                }
            } catch (Exception e) {
                running = false;
                process = null;
                String message = e.getMessage() == null ? "QEMU не запустился" : e.getMessage();
                log("sandbox start error: " + message);
                runJs("window.__sandboxError && window.__sandboxError("
                        + JSONObject.quote(message) + "," + JSONObject.quote(activeChatId) + ")");
                return;
            }
        }

        log("sandbox start " + VERSION + " chat=" + currentChat);
        runJs("window.__sandboxStarted && window.__sandboxStarted("
                + JSONObject.quote(currentChat) + ")");

        new Thread(() -> {
            File logFile = rotateLogIfNeeded(new File(activity.getCacheDir(), "sandbox.log"));
            try (InputStream rawIn = started.getInputStream();
                 InputStreamReader reader = new InputStreamReader(rawIn, StandardCharsets.UTF_8);
                 OutputStream logOut = new FileOutputStream(logFile, true)) {
                char[] cbuf = new char[4096];
                int read;
                while ((read = reader.read(cbuf)) > 0) {
                    String text = new String(cbuf, 0, read);
                    logOut.write(text.getBytes(StandardCharsets.UTF_8));
                    logOut.flush();
                    synchronized (serialLock) {
                        serial.append(text);
                        if (!shellReady && networkConfigured
                                && serial.indexOf(NET_READY_MARKER) >= 0) {
                            shellReady = true;
                        }
                        if (serial.length() > 400000) {
                            serial.delete(0, serial.length() - 300000);
                        }
                        serialLock.notifyAll();
                    }
                    maybeConfigureNetwork(started);
                    runJs("window.__sandboxOutput && window.__sandboxOutput("
                            + JSONObject.quote(text) + ")");
                }
            } catch (Exception e) {
                log("sandbox output error: " + e.getMessage());
            }

            int code = -1;
            try {
                code = started.waitFor();
            } catch (Exception ignored) {
            }
            String exitedChat;
            synchronized (startLock) {
                if (process == started) {
                    process = null;
                }
                running = false;
                shellReady = false;
                exitedChat = activeChatId;
                activeChatId = "";
            }
            synchronized (serialLock) {
                serialLock.notifyAll();
            }
            log("sandbox exit code=" + code);
            runJs("window.__sandboxExit && window.__sandboxExit(" + code + ","
                    + JSONObject.quote(exitedChat) + ")");
        }, "sandbox-output").start();
    }

    void exec(String id, String command) {
        final Process proc = process;
        if (!running || proc == null) {
            runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                    + JSONObject.quote(id) + "," + JSONObject.quote(
                    "Песочница не запущена") + ",-1)");
            return;
        }
        new Thread(() -> {
            synchronized (execLock) {
                if (!running || process != proc) {
                    runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                            + JSONObject.quote(id) + "," + JSONObject.quote(
                            "Песочница не запущена") + ",-1)");
                    return;
                }

                // Wait for the guest OS + network setup to finish before pushing
                // commands into the serial console.
                long bootDeadline = System.currentTimeMillis() + 60_000L;
                synchronized (serialLock) {
                    while (!shellReady && running && process == proc
                            && System.currentTimeMillis() < bootDeadline) {
                        try {
                            serialLock.wait(250);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!running || process != proc) {
                    runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                            + JSONObject.quote(id) + "," + JSONObject.quote(
                            "Песочница остановлена") + ",-1)");
                    return;
                }

                String rawId = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
                if (rawId.isEmpty()) rawId = String.valueOf(System.nanoTime());
                String begin = "__LOCALAPI_BEGIN_" + rawId + "__";
                String end = "__LOCALAPI_END_" + rawId + "__";
                String tmpFile = "/tmp/.cmd_" + rawId + ".sh";
                String cmdText = command == null ? "" : command;
                String b64 = Base64.encodeToString(
                        cmdText.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

                synchronized (serialLock) {
                    serial.setLength(0);
                }

                try {
                    // Run via a base64-decoded temporary script in a subshell so
                    // multi-line scripts, quotes, '#' comments, 'set -e' or 'exit'
                    // never break or kill PID 1, while preserving 'cd' via trap.
                    String script = "printf '%s' '" + b64 + "' | "
                            + "(base64 -d 2>/dev/null || /bin/busybox base64 -d) > " + tmpFile
                            + " && printf '\\n" + begin + "\\n'"
                            + " && ( trap 'pwd > /tmp/.localapi_cwd 2>/dev/null' EXIT; . " + tmpFile + " )"
                            + "; __rc=$?; rm -f " + tmpFile
                            + "; [ -f /tmp/.localapi_cwd ] && cd \"$(cat /tmp/.localapi_cwd)\" 2>/dev/null"
                            + "; printf '\\n" + end + "%s\\n' \"$__rc\"\n";

                    synchronized (writeLock) {
                        proc.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
                        proc.getOutputStream().flush();
                    }

                    long timeoutMs = (cmdText.contains("install-base")
                            || cmdText.contains("apt-get")) ? 900_000L : 120_000L;
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    int endAt = -1;
                    int code = -1;
                    String lastChunk = "";

                    while (running && process == proc && System.currentTimeMillis() < deadline) {
                        String partial = null;
                        synchronized (serialLock) {
                            String buffer = serial.toString();
                            for (int pos = buffer.indexOf(end); pos >= 0;
                                 pos = buffer.indexOf(end, pos + 1)) {
                                int codeStart = pos + end.length();
                                int nl = buffer.indexOf('\n', codeStart);
                                String codeText = buffer.substring(codeStart,
                                        nl >= 0 ? nl : buffer.length()).trim();
                                if (codeText.matches("\\d+")) {
                                    endAt = pos;
                                    try {
                                        code = Integer.parseInt(codeText);
                                    } catch (Exception ignored) {
                                    }
                                    break;
                                }
                            }
                            if (endAt >= 0) break;
                            int beginAt = buffer.lastIndexOf(begin);
                            if (beginAt >= 0) {
                                partial = buffer.substring(beginAt + begin.length());
                            }
                            serialLock.wait(200);
                        }
                        if (partial != null) {
                            String chunk = cleanShellOutput(
                                    partial.replace(begin, "").replace(end, ""));
                            if (chunk.length() > 16000) {
                                chunk = chunk.substring(0, 16000) + "\n[вывод обрезан]";
                            }
                            if (!chunk.equals(lastChunk)) {
                                lastChunk = chunk;
                                runJs("window.__sandboxExecChunk && window.__sandboxExecChunk("
                                        + JSONObject.quote(id) + ","
                                        + JSONObject.quote(chunk) + ")");
                            }
                        }
                    }

                    boolean timedOut = (endAt < 0 && running && process == proc);
                    if (timedOut) {
                        // Interrupt the stuck foreground job so subsequent execs
                        // get a clean prompt back.
                        try {
                            synchronized (writeLock) {
                                proc.getOutputStream().write(new byte[]{3, '\n'});
                                proc.getOutputStream().flush();
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    String buffer;
                    synchronized (serialLock) {
                        buffer = serial.toString();
                    }
                    String output;
                    if (endAt >= 0) {
                        int beginAt = buffer.lastIndexOf(begin, endAt);
                        int from = beginAt >= 0 ? beginAt + begin.length() : 0;
                        output = buffer.substring(from, endAt);
                    } else {
                        int beginAt = buffer.lastIndexOf(begin);
                        output = beginAt >= 0 ? buffer.substring(beginAt + begin.length()) : buffer;
                        if (output.length() > 12000) {
                            output = output.substring(output.length() - 12000);
                        }
                    }
                    output = cleanShellOutput(output.replace(begin, "").replace(end, ""));
                    if (timedOut) {
                        output = (output.isEmpty() ? "" : output + "\n")
                                + "[таймаут выполнения команды]";
                    }
                    if (output.length() > 16000) {
                        output = output.substring(0, 16000) + "\n[вывод обрезан]";
                    }
                    runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                            + JSONObject.quote(id) + "," + JSONObject.quote(output)
                            + "," + code + ")");
                } catch (Exception e) {
                    runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                            + JSONObject.quote(id) + "," + JSONObject.quote(
                            e.getMessage() == null ? "exec error" : e.getMessage())
                            + ",-1)");
                }
            }
        }, "sandbox-exec").start();
    }

    private static String cleanShellOutput(String output) {
        if (output == null || output.isEmpty()) return "";
        String normalized = output.replace("\r\n", "\n").replace('\r', '\n');
        normalized = ANSI_PATTERN.matcher(normalized).replaceAll("");
        StringBuilder cleaned = new StringBuilder(normalized.length());
        for (String line : normalized.split("\n", -1)) {
            if (line.equals("#") || line.equals("# ")) continue;
            cleaned.append(line).append('\n');
        }
        return cleaned.toString().trim();
    }

    private void maybeConfigureNetwork(Process proc) {
        if (networkConfigured || proc == null) return;
        synchronized (serialLock) {
            String tail = serial.length() > 8
                    ? serial.substring(serial.length() - 8) : serial.toString();
            if (!tail.endsWith("# ") && !tail.endsWith("#")) return;
            if (networkConfigured) return;
            networkConfigured = true;
        }
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = utc.format(new Date());
        // Split the marker string in printf so the tty echo doesn't trigger shellReady early.
        String setup = "/bin/busybox ifconfig lo up 2>/dev/null\n"
                + "/bin/busybox ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null\n"
                + "/bin/busybox route add default gw 10.0.2.2 eth0 2>/dev/null\n"
                + "printf 'nameserver 10.0.2.3\\n' > /etc/resolv.conf\n"
                + "date -u -s \"" + date + "\" 2>/dev/null\n"
                + "export http_proxy=http://10.0.2.2:18991\n"
                + "export https_proxy=http://10.0.2.2:18991\n"
                + "[ -e /usr/bin/top ] || ln -s /bin/busybox /usr/bin/top 2>/dev/null\n"
                + "printf '#!/bin/sh\\napt-get update && apt-get install -y --no-install-recommends curl ca-certificates htop procps python3 python3-pip git wget dnsutils iputils-ping\\n' > /usr/bin/install-base\n"
                + "chmod +x /usr/bin/install-base\n"
                + "printf 'Acquire::http::Proxy \"http://10.0.2.2:18991\";\\n"
                + "Acquire::https::Proxy \"http://10.0.2.2:18991\";\\n' > /etc/apt/apt.conf.d/99proxy\n"
                + "cd /workspace 2>/dev/null || true\n"
                + "printf '__LOCALAPI_' 'NET_READY__\\n'\n";
        try {
            synchronized (writeLock) {
                proc.getOutputStream().write(setup.getBytes(StandardCharsets.UTF_8));
                proc.getOutputStream().flush();
            }
            log("sandbox network configured (slirp 10.0.2.15)");
        } catch (Exception e) {
            shellReady = true;
            log("sandbox network setup error: " + e.getMessage());
        }
    }

    void stop() {
        Process p;
        synchronized (startLock) {
            p = process;
            process = null;
            running = false;
            shellReady = false;
        }
        if (p != null) p.destroy();
        synchronized (serialLock) {
            serialLock.notifyAll();
        }
        log("sandbox stop");
    }

    void write(String text) {
        Process p = process;
        if (p == null || text == null) return;
        try {
            synchronized (writeLock) {
                p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            }
        } catch (Exception e) {
            log("sandbox stdin error: " + e.getMessage());
        }
    }

    private void download(File target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ASSET_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LocalApiDeepSeek");
        connection.setUseCaches(false);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long readTotal = 0;
            int read;
            int lastPercent = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                readTotal += read;
                if (total > 0) {
                    int percent = (int) (readTotal * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        runJs("window.__sandboxProgress && window.__sandboxProgress("
                                + percent + ")");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void unzip(File zip, File dir) throws IOException {
        List<File> written = new ArrayList<>();
        boolean ok = false;
        try {
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(
                    new FileInputStream(zip)))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().replace('\\', '/');
                    while (name.startsWith("./")) name = name.substring(2);
                    while (name.startsWith("/")) name = name.substring(1);
                    int slash = name.lastIndexOf('/');
                    String baseName = slash >= 0 ? name.substring(slash + 1) : name;
                    if (baseName.isEmpty() || ".".equals(baseName) || "..".equals(baseName)) {
                        continue;
                    }
                    File target;
                    if (baseName.equals("vmlinuz") || baseName.startsWith("vmlinuz-")) {
                        target = new File(dir, "vmlinuz");
                    } else if (baseName.equals("ubuntu-initramfs.cpio.gz")
                            || baseName.equals("initramfs.cpio.gz")) {
                        target = new File(dir, "initramfs.cpio.gz");
                    } else if (baseName.equals("rootfs.img")) {
                        target = new File(dir, "rootfs.img");
                    } else if (name.startsWith("roms/") || name.contains("/roms/")
                            || name.startsWith("qemu-data/") || name.contains("/qemu-data/")
                            || baseName.endsWith(".rom")) {
                        File qemuData = new File(dir, "qemu-data");
                        if (!qemuData.isDirectory() && !qemuData.mkdirs()) {
                            throw new IOException("Не удалось создать qemu-data");
                        }
                        target = new File(qemuData, baseName);
                    } else {
                        continue;
                    }
                    written.add(target);
                    try (OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = zin.read(buffer)) > 0) out.write(buffer, 0, read);
                    }
                }
            }
            List<String> missing = new ArrayList<>();
            if (!new File(dir, "vmlinuz").isFile()) missing.add("vmlinuz");
            if (!new File(dir, "rootfs.img").isFile()) missing.add("rootfs.img");
            if (!new File(dir, "qemu-data/efi-virtio.rom").isFile()) {
                missing.add("ROM (efi-virtio.rom)");
            }
            if (!missing.isEmpty()) {
                throw new IOException("В архиве нет: " + String.join(", ", missing));
            }
            ok = true;
        } finally {
            if (!ok) {
                for (File f : written) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String tail(File file, int max) {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            long skip = Math.max(0, length - max);
            long skipped = 0;
            while (skipped < skip && in.skip(skip - skipped) > 0) skipped++;
            byte[] data = new byte[(int) Math.min(max, length)];
            int read = in.read(data);
            return read > 0 ? new String(data, 0, read, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static File rotateLogIfNeeded(File logFile) {
        if (logFile.isFile() && logFile.length() > MAX_LOG_BYTES) {
            File old = new File(logFile.getParentFile(), "sandbox.log.old");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(old);
        }
        return logFile;
    }

    private void log(String message) {
        File logFile = rotateLogIfNeeded(new File(activity.getCacheDir(), "sandbox.log"));
        try (OutputStream out = new FileOutputStream(logFile, true)) {
            out.write((System.currentTimeMillis() + " " + message + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void runJs(String js) {
        activity.runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }
}
