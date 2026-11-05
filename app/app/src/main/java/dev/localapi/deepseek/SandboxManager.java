package dev.localapi.deepseek;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the Ubuntu Base/QEMU guest assets and launches QEMU with the
 * app-private agent workspace shared over 9p.
 *
 * QEMU itself is packaged in jniLibs (arm64-v8a). There is no KVM on Android,
 * so the guest runs under TCG and is slow. All output is forwarded to the
 * WebView journal; the first milestone is a bootable terminal guest.
 */
final class SandboxManager {
    static final String VERSION = "ubuntu-2";
    static final String ASSET_URL =
            "https://github.com/Rewixx-png/localapi-deepseek/releases/download/"
                    + "sandbox-ubuntu-2/ubuntu-sandbox.zip";
    static final String ASSET_SHA =
            "ab2d5073b5eff5b607e023abba65cb6f80244c79550f2bb803e09697638c7cd8";
    static final long ASSET_SIZE = 52260768L;

    private final Activity activity;
    private final WebView web;
    private volatile Process process;
    private volatile boolean installing;
    private volatile boolean running;
    private final Object serialLock = new Object();
    private final StringBuilder serial = new StringBuilder();
    private final Object execLock = new Object();
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
            // Leave 1 GiB for Android; never offer more than the device has.
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
                && new File(dir, "initramfs.cpio.gz").isFile()
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
        if (running) {
            runJs("window.__sandboxOutput && window.__sandboxOutput("
                    + JSONObject.quote("[sandbox уже запущена]") + ")");
            return;
        }
        try {
            loadConfig();
            File dir = dir(activity);
            String libs = activity.getApplicationInfo().nativeLibraryDir;
            File qemu = new File(libs, "libqemu-system-aarch64.so");
            if (!qemu.isFile()) {
                throw new IOException("QEMU не упакован в APK для этой ABI");
            }
            File workspace = AgentWorkspace.root(activity, chatId);
            String cmd = "/system/bin/linker64";
            ProcessBuilder pb = new ProcessBuilder(
                    cmd,
                    qemu.getAbsolutePath(),
                    "-M", "virt",
                    "-cpu", "max",
                    "-m", String.valueOf(ramMb),
                    "-smp", String.valueOf(cores),
                    "-nographic",
                    "-no-reboot",
                    "-L", new File(dir, "qemu-data").getAbsolutePath(),
                    "-kernel", new File(dir, "vmlinuz").getAbsolutePath(),
                    "-initrd", new File(dir, "initramfs.cpio.gz").getAbsolutePath(),
                    "-append", "console=ttyAMA0 rdinit=/init loglevel=7",
                    "-virtfs", "local,path=" + workspace.getAbsolutePath()
                            + ",mount_tag=workspace,security_model=none"
            );
            pb.redirectErrorStream(true);
            pb.directory(activity.getCacheDir());
            pb.environment().put("LD_LIBRARY_PATH", libs);
            pb.environment().put("HOME", dir.getAbsolutePath());
            pb.environment().put("TMPDIR", activity.getCacheDir().getAbsolutePath());
            process = pb.start();
            running = true;
            synchronized (serialLock) {
                serial.setLength(0);
            }
            log("sandbox start " + VERSION + " chat=" + chatId);
            runJs("window.__sandboxStarted && window.__sandboxStarted()");
            new Thread(() -> {
                File logFile = new File(activity.getCacheDir(), "sandbox.log");
                try (InputStream in = process.getInputStream();
                     OutputStream out = new FileOutputStream(logFile, true)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        out.flush();
                        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        synchronized (serialLock) {
                            serial.append(text);
                            if (serial.length() > 400000) {
                                serial.delete(0, serial.length() - 300000);
                            }
                            serialLock.notifyAll();
                        }
                        runJs("window.__sandboxOutput && window.__sandboxOutput("
                                + JSONObject.quote(text) + ")");
                    }
                } catch (Exception e) {
                    log("sandbox output error: " + e.getMessage());
                }
                int code = -1;
                try {
                    code = process.waitFor();
                } catch (Exception ignored) {
                }
                running = false;
                log("sandbox exit code=" + code);
                runJs("window.__sandboxExit && window.__sandboxExit(" + code + ")");
            }, "sandbox-output").start();
        } catch (Exception e) {
            running = false;
            String message = e.getMessage() == null ? "QEMU не запустился" : e.getMessage();
            log("sandbox start error: " + message);
            runJs("window.__sandboxError && window.__sandboxError("
                    + JSONObject.quote(message) + ")");
        }
    }

    void exec(String id, String command) {
        if (!running || process == null) {
            runJs("window.__sandboxExecResult && window.__sandboxExecResult("
                    + JSONObject.quote(id) + "," + JSONObject.quote(
                    "Песочница не запущена") + ",-1)");
            return;
        }
        new Thread(() -> {
            synchronized (execLock) {
                String begin = "__LOCALAPI_BEGIN_" + id + "__";
                String end = "__LOCALAPI_END_" + id + "__";
                synchronized (serialLock) {
                    serial.setLength(0);
                }
                try {
                    String script = "printf '\\n" + begin + "\\n'; " + command
                            + "\n__rc=$?; printf '\\n" + end + "%s\\n' \"$__rc\"\n";
                    process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                    long deadline = System.currentTimeMillis() + 120_000;
                    int endAt = -1;
                    int code = -1;
                    while (System.currentTimeMillis() < deadline) {
                        synchronized (serialLock) {
                            String buffer = serial.toString();
                            // The interactive shell echoes the command, including
                            // the marker text. Pick an END occurrence followed by a
                            // numeric exit code; the echoed "%s" is ignored.
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
                            serialLock.wait(200);
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
                        output = buffer;
                        if (output.length() > 12000) {
                            output = output.substring(output.length() - 12000);
                        }
                    }
                    output = output.replace(begin, "").replace(end, "").trim();
                    output = cleanShellOutput(output, command);
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

    private static String cleanShellOutput(String output, String command) {
        String echoed = command == null ? "" : command.trim();
        StringBuilder cleaned = new StringBuilder(output.length());
        boolean first = true;
        for (String line : output.split("\n", -1)) {
            String trimmed = line.trim();
            // Interactive QEMU console prints its root prompt into the serial
            // capture. It is not command output and confuses the model.
            if (line.equals("#") || line.equals("# ")) continue;
            if (first && line.startsWith("# ")) line = line.substring(2);
            // The shell can echo the command line back on the serial console.
            if (!echoed.isEmpty() && trimmed.equals(echoed)) continue;
            if (trimmed.startsWith("__rc=$?;")) continue;
            cleaned.append(line).append('\n');
            if (!trimmed.isEmpty()) first = false;
        }
        return cleaned.toString().trim();
    }

    void stop() {
        Process p = process;
        process = null;
        running = false;
        if (p != null) p.destroy();
        log("sandbox stop");
    }

    void write(String text) {
        Process p = process;
        if (p == null) return;
        try {
            p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
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
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                File target;
                if (name.endsWith("vmlinuz-6.11.0-17-generic")
                        || name.equals("vmlinuz")) {
                    target = new File(dir, "vmlinuz");
                } else if (name.endsWith("ubuntu-initramfs.cpio.gz")) {
                    target = new File(dir, "initramfs.cpio.gz");
                } else if (name.startsWith("roms/")) {
                    File qemuData = new File(dir, "qemu-data");
                    if (!qemuData.isDirectory() && !qemuData.mkdirs()) {
                        throw new IOException("Не удалось создать qemu-data");
                    }
                    target = new File(qemuData, new File(name).getName());
                } else {
                    continue;
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zin.read(buffer)) > 0) out.write(buffer, 0, read);
                }
            }
        }
        if (!new File(dir, "vmlinuz").isFile()
                || !new File(dir, "initramfs.cpio.gz").isFile()
                || !new File(dir, "qemu-data/efi-virtio.rom").isFile()) {
            throw new IOException("В архиве нет vmlinuz/initramfs/ROM");
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

    private void log(String message) {
        try (OutputStream out = new FileOutputStream(
                new File(activity.getCacheDir(), "sandbox.log"), true)) {
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
