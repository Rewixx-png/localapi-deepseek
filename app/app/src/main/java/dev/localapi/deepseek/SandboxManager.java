package dev.localapi.deepseek;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the Ubuntu Base/QEMU guest assets, launches QEMU with the
 * app-private agent workspace shared over 9p, configures non-interactive apt +
 * proxy networking, detects installed tools, and runs base package setup.
 */
final class SandboxManager {
    static final String VERSION = AppConfig.SANDBOX_VERSION;
    static final String ASSET_URL = AppConfig.SANDBOX_ASSET_URL;
    static final String ASSET_SHA = AppConfig.SANDBOX_ASSET_SHA256;
    static final long ASSET_SIZE = AppConfig.SANDBOX_ASSET_SIZE;

    private static final String NET_READY_MARKER = "__LOCALAPI_NET_READY__";
    private static final Pattern TOOLS_MARKER_RE =
            Pattern.compile("__LOCALAPI_TOOLS__=([^\\r\\n]*)");
    private static final Pattern BASE_MARKER_RE =
            Pattern.compile("__LOCALAPI_BASE_V2__=([01])");
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static final Pattern ANSI_PATTERN =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    static final class Status {
        final boolean installed;
        final boolean installing;
        final boolean running;
        final boolean shellReady;
        final boolean baseInstalled;
        final boolean installingBase;
        final String installedTools;
        final String networkStatus;
        final String version;
        final long size;
        final int ramMb;
        final int cores;
        final int maxRamMb;
        final int maxCores;
        final String abi;
        final String log;

        Status(
                boolean installed,
                boolean installing,
                boolean running,
                boolean shellReady,
                boolean baseInstalled,
                boolean installingBase,
                String installedTools,
                String networkStatus,
                String version,
                long size,
                int ramMb,
                int cores,
                int maxRamMb,
                int maxCores,
                String abi,
                String log
        ) {
            this.installed = installed;
            this.installing = installing;
            this.running = running;
            this.shellReady = shellReady;
            this.baseInstalled = baseInstalled;
            this.installingBase = installingBase;
            this.installedTools = installedTools;
            this.networkStatus = networkStatus;
            this.version = version;
            this.size = size;
            this.ramMb = ramMb;
            this.cores = cores;
            this.maxRamMb = maxRamMb;
            this.maxCores = maxCores;
            this.abi = abi;
            this.log = log;
        }
    }

    interface Listener {
        void onProgress(int percent);
        void onInstalled();
        void onError(String message, String chatId);
        void onStarted(String chatId);
        void onOutput(String text);
        void onExecChunk(String id, String chunk);
        void onExecResult(String id, String output, int code);
        void onExit(int code, String chatId);
        default void onStatusChanged() {}
    }

    private final Activity activity;
    private volatile Listener listener;
    private volatile Process process;
    private volatile boolean installing;
    private volatile boolean running;
    private volatile boolean online = true;
    private volatile boolean networkConfigured;
    private volatile boolean shellReady;
    private volatile boolean baseInstalled;
    private volatile boolean installingBase;
    private volatile String installedTools = "";
    private volatile String networkStatus = "";
    private volatile String activeChatId = "";

    private final Object startLock = new Object();
    private final Object serialLock = new Object();
    private final StringBuilder serial = new StringBuilder();
    private final Object execLock = new Object();
    private final Object writeLock = new Object();
    private final Object ticketLock = new Object();
    private volatile long execTicket = 0;

    private static final String PREFS = "sandbox_config";
    private static final String KEY_RAM = "ram_mb";
    private static final String KEY_CORES = "cores";
    private int ramMb = 2048;
    private int cores = 2;

    SandboxManager(Activity activity) {
        this.activity = activity;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setOnline(boolean enabled) {
        this.online = enabled;
        SandboxProxy.setOnline(enabled);
    }

    private void ui(Runnable r) {
        activity.runOnUiThread(r);
    }

    private void notifyStatus() {
        ui(() -> {
            Listener l = listener;
            if (l != null) l.onStatusChanged();
        });
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
            if (am != null) {
                am.getMemoryInfo(info);
                long total = info.totalMem / (1024L * 1024L);
                if (total < 1024) total = 1024;
                return (int) Math.min(6144L, Math.max(512L, total - 1024L));
            }
        } catch (Exception ignored) {
        }
        return 2048;
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

    Status setConfig(int ramMb, int cores) {
        this.ramMb = Math.max(512, Math.min(maxRamMb(), ramMb));
        this.cores = Math.max(1, Math.min(maxCores(), cores));
        prefs(activity).edit()
                .putInt(KEY_RAM, this.ramMb)
                .putInt(KEY_CORES, this.cores)
                .apply();
        return snapshot();
    }

    static boolean installed(Context context) {
        File dir = dir(context);
        return new File(dir, "vmlinuz").isFile()
                && new File(dir, "rootfs.img").isFile()
                && new File(dir, "qemu-data/efi-virtio.rom").isFile();
    }

    Status snapshot() {
        loadConfig();
        File logFile = new File(activity.getCacheDir(), "sandbox.log");
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "";
        return new Status(
                installed(activity),
                installing,
                running,
                shellReady,
                baseInstalled,
                installingBase,
                installedTools,
                networkStatus,
                VERSION,
                ASSET_SIZE,
                ramMb,
                cores,
                maxRamMb(),
                maxCores(),
                abi,
                tail(logFile, 64 * 1024)
        );
    }

    void install() {
        if (installing) return;
        if (installed(activity)) {
            ui(() -> { Listener l = listener; if (l != null) l.onInstalled(); });
            return;
        }
        if (!online) {
            ui(() -> { Listener l = listener; if (l != null) l.onError("Доступ к интернету выключен", ""); });
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
                ui(() -> { Listener l = listener; if (l != null) l.onInstalled(); });
                log("sandbox installed " + VERSION);
            } catch (Exception e) {
                installing = false;
                String message = e.getMessage() == null
                        ? "Не удалось установить песочницу"
                        : e.getMessage();
                log("sandbox install error: " + message);
                ui(() -> { Listener l = listener; if (l != null) l.onError(message, ""); });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
            }
        }, "sandbox-install").start();
    }

    void start(String chatId) {
        if (!installed(activity)) {
            final String cid = chatId == null ? "" : chatId;
            ui(() -> { Listener l = listener; if (l != null) l.onError("Сначала скачайте песочницу", cid); });
            return;
        }
        if (online) {
            SandboxProxy.start();
        }
        final Process started;
        final String currentChat;
        synchronized (startLock) {
            if (running) {
                emitOutput("[sandbox уже запущена]\n");
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
                installingBase = false;
                networkStatus = "";
                synchronized (serialLock) {
                    serial.setLength(0);
                }
            } catch (Exception e) {
                running = false;
                process = null;
                SandboxProxy.stop();
                String message = e.getMessage() == null ? "QEMU не запустился" : e.getMessage();
                log("sandbox start error: " + message);
                final String errChat = activeChatId;
                ui(() -> { Listener l = listener; if (l != null) l.onError(message, errChat); });
                return;
            }
        }

        log("sandbox start " + VERSION + " chat=" + currentChat);
        ui(() -> { Listener l = listener; if (l != null) l.onStarted(currentChat); });

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
                    boolean statusUpdated = false;
                    synchronized (serialLock) {
                        serial.append(text);
                        if (!shellReady && networkConfigured
                                && serial.indexOf(NET_READY_MARKER) >= 0) {
                            shellReady = true;
                            statusUpdated = true;
                        }
                        if (networkConfigured && text.contains("__LOCALAPI_")) {
                            String snapshot = serial.length() > 4096
                                    ? serial.substring(serial.length() - 4096)
                                    : serial.toString();
                            Matcher tm = TOOLS_MARKER_RE.matcher(snapshot);
                            while (tm.find()) {
                                String found = tm.group(1).trim();
                                if (!found.equals(installedTools)) {
                                    installedTools = found;
                                    statusUpdated = true;
                                }
                            }
                            Matcher bm = BASE_MARKER_RE.matcher(snapshot);
                            while (bm.find()) {
                                boolean ok = "1".equals(bm.group(1));
                                if (ok != baseInstalled) {
                                    baseInstalled = ok;
                                    statusUpdated = true;
                                }
                            }
                        }
                        if (serial.length() > 400000) {
                            serial.delete(0, serial.length() - 300000);
                        }
                        serialLock.notifyAll();
                    }
                    if (statusUpdated) notifyStatus();
                    maybeConfigureNetwork(started);
                    ui(() -> { Listener l = listener; if (l != null) l.onOutput(text); });
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
                installingBase = false;
                exitedChat = activeChatId;
                activeChatId = "";
            }
            SandboxProxy.stop();
            synchronized (serialLock) {
                serialLock.notifyAll();
            }
            log("sandbox exit code=" + code);
            final int exitCode = code;
            ui(() -> { Listener l = listener; if (l != null) l.onExit(exitCode, exitedChat); });
        }, "sandbox-output").start();
    }

    /** Interrupts any currently executing shell command with Ctrl+C without killing QEMU. */
    void interruptExec() {
        synchronized (ticketLock) {
            execTicket++;
        }
        Process p = process;
        if (p != null) {
            try {
                synchronized (writeLock) {
                    p.getOutputStream().write(new byte[]{3, 3, '\n'});
                    p.getOutputStream().flush();
                }
            } catch (Exception ignored) { }
        }
        synchronized (serialLock) {
            serialLock.notifyAll();
        }
    }

    /** Tests host proxy and (if running) guest internet access through guest proxy. */
    void testNetwork() {
        if (!online) {
            networkStatus = "Доступ к интернету выключен";
            notifyStatus();
            return;
        }
        networkStatus = "Проверка…";
        notifyStatus();
        new Thread(() -> {
            String proxyResult = SandboxProxy.selfTest();
            emitOutput("\n[net-test] Host proxy 127.0.0.1:" + SandboxProxy.PORT + " -> " + proxyResult + "\n");
            if (!running) {
                networkStatus = proxyResult;
                notifyStatus();
                return;
            }
            String proxyUrl = AppConfig.SANDBOX_GUEST_PROXY_URL;
            String cmd = "echo \"[net-test] Checking guest -> " + proxyUrl + "...\"; "
                    + "if command -v curl >/dev/null 2>&1; then "
                    + "  curl -sS -I --max-time 12 -x " + proxyUrl + " http://ports.ubuntu.com/ubuntu-ports/ | head -1 && "
                    + "  curl -sS -I --max-time 12 -x " + proxyUrl + " https://example.com | head -1; "
                    + "elif /bin/busybox --list 2>/dev/null | grep -qx wget; then "
                    + "  http_proxy=" + proxyUrl + " /bin/busybox wget -S --spider -T 12 http://ports.ubuntu.com/ubuntu-ports/ 2>&1 | head -4; "
                    + "else "
                    + "  printf 'HEAD http://ports.ubuntu.com/ubuntu-ports/ HTTP/1.1\\r\\nHost: ports.ubuntu.com\\r\\nConnection: close\\r\\n\\r\\n' | /bin/busybox nc -w 10 "
                    + AppConfig.SANDBOX_GUEST_PROXY_HOST + " " + AppConfig.SANDBOX_PROXY_PORT + " | head -1; "
                    + "fi";
            ExecOutcome outcome = runBlockingExec("nettest_" + System.currentTimeMillis(), cmd, 25_000L, null);
            if (outcome.code == 0 && (outcome.output.contains("HTTP/") || outcome.output.contains("200")
                    || outcome.output.contains("301") || outcome.output.contains("302"))) {
                networkStatus = "OK (гость + прокси работают)";
            } else {
                String summary = outcome.output.isEmpty() ? ("код " + outcome.code) : outcome.output.split("\\n")[0];
                networkStatus = "Проблема: " + summary;
            }
            emitOutput("[net-test] Итог: " + networkStatus + "\n");
            notifyStatus();
        }, "sandbox-nettest").start();
    }

    /** Installs Python, wget, curl, htop, git, jq and CLI utilities persistently into rootfs.img. */
    void installBasePackages() {
        if (!running || installingBase) return;
        if (!online) {
            emitOutput("\n==> Нельзя установить пакеты: офлайн-режим.\n");
            return;
        }
        installingBase = true;
        notifyStatus();
        new Thread(() -> {
            emitOutput("\n==> Запуск /usr/bin/install-base (сохранится в rootfs.img)...\n");
            String cmd = "/usr/bin/install-base && "
                    + "__tools=\"\"; for t in python3 pip3 curl wget htop git jq unzip zip nano tree sqlite3; do "
                    + "  [ -x \"/usr/bin/$t\" ] || [ -x \"/bin/$t\" ] && __tools=\"${__tools}${__tools:+,}$t\"; "
                    + "done; "
                    + "printf '%s%s=%s\\n' '__LOCALAPI_' 'TOOLS__' \"$__tools\"; "
                    + "[ -f /var/lib/localapi-base-v2 ] && printf '%s%s=1\\n' '__LOCALAPI_' 'BASE_V2__'";
            ExecOutcome outcome = runBlockingExec("installbase_" + System.currentTimeMillis(), cmd, 900_000L, null);
            installingBase = false;
            if (outcome.code == 0) {
                baseInstalled = true;
                emitOutput("\n==> Базовые пакеты успешно установлены!\n");
            } else {
                emitOutput("\n==> Установка завершилась с кодом " + outcome.code + "\n");
            }
            notifyStatus();
        }, "sandbox-install-base").start();
    }

    private void emitOutput(String text) {
        ui(() -> {
            Listener l = listener;
            if (l != null) l.onOutput(text);
        });
    }

    /**
     * Paces bytes into QEMU PL011 ttyAMA0 (16/32-byte RX FIFO, no RTS/CTS)
     * so long scripts never drop characters under TCG emulation.
     */
    private static void writeUartSafe(OutputStream out, byte[] bytes) throws IOException {
        int off = 0;
        while (off < bytes.length) {
            int len = Math.min(24, bytes.length - off);
            out.write(bytes, off, len);
            out.flush();
            off += len;
            if (off < bytes.length) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }
    }

    private static final class ExecOutcome {
        final String output;
        final int code;
        final boolean preempted;

        ExecOutcome(String output, int code, boolean preempted) {
            this.output = output;
            this.code = code;
            this.preempted = preempted;
        }
    }

    private ExecOutcome runBlockingExec(String id, String command, long timeoutMs, String streamIdForChunks) {
        final Process proc = process;
        if (!running || proc == null) {
            return new ExecOutcome("Песочница не запущена", -1, false);
        }
        final long myTicket;
        synchronized (ticketLock) {
            myTicket = ++execTicket;
        }
        synchronized (execLock) {
            if (myTicket != execTicket) return new ExecOutcome("", -1, true);
            if (!running || process != proc) {
                return new ExecOutcome("Песочница не запущена", -1, false);
            }

            long bootStart = System.currentTimeMillis();
            long bootDeadline = bootStart + 25_000L;
            boolean nudged = false;
            while (!shellReady && running && process == proc
                    && myTicket == execTicket
                    && System.currentTimeMillis() < bootDeadline) {
                if (!nudged && !networkConfigured
                        && System.currentTimeMillis() - bootStart > 4_000L) {
                    nudged = true;
                    try {
                        synchronized (writeLock) {
                            proc.getOutputStream().write('\n');
                            proc.getOutputStream().flush();
                        }
                    } catch (Exception ignored) { }
                }
                synchronized (serialLock) {
                    if (shellReady) break;
                    try {
                        serialLock.wait(200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (myTicket != execTicket) return new ExecOutcome("", -1, true);
            if (!running || process != proc) {
                return new ExecOutcome("Песочница остановлена", -1, false);
            }

            String rawId = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
            if (rawId.isEmpty()) rawId = String.valueOf(System.nanoTime());
            String begin = "__LOCALAPI_BEGIN_" + rawId + "__";
            String end = "__LOCALAPI_END_" + rawId + "__";
            String tmpFile = "/tmp/.cmd_" + rawId + ".sh";
            String b64File = tmpFile + ".b64";
            String cmdText = command == null ? "" : command;
            String b64 = Base64.encodeToString(
                    cmdText.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            synchronized (serialLock) {
                serial.setLength(0);
            }

            try {
                StringBuilder sb = new StringBuilder();
                sb.append("rm -f ").append(b64File).append(' ').append(tmpFile).append('\n');
                for (int off = 0; off < b64.length(); off += 240) {
                    int endOff = Math.min(b64.length(), off + 240);
                    sb.append("printf '%s' '").append(b64, off, endOff)
                            .append("' >> ").append(b64File).append('\n');
                }
                sb.append("(base64 -d ").append(b64File)
                        .append(" 2>/dev/null || /bin/busybox base64 -d ")
                        .append(b64File).append(") > ").append(tmpFile)
                        .append(" && rm -f ").append(b64File)
                        .append(" && printf '\\n").append(begin).append("\\n'")
                        .append(" && ( trap 'pwd > /tmp/.localapi_cwd 2>/dev/null' EXIT; . ")
                        .append(tmpFile).append(" )")
                        .append("; __rc=$?; rm -f ").append(tmpFile)
                        .append("; [ -f /tmp/.localapi_cwd ] && cd \"$(cat /tmp/.localapi_cwd)\" 2>/dev/null")
                        .append("; printf '\\n").append(end).append("%s\\n' \"$__rc\"\n");

                synchronized (writeLock) {
                    writeUartSafe(proc.getOutputStream(),
                            sb.toString().getBytes(StandardCharsets.UTF_8));
                }

                long deadline = System.currentTimeMillis() + timeoutMs;
                int endAt = -1;
                int code = -1;
                String lastChunk = "";

                while (running && process == proc && myTicket == execTicket
                        && System.currentTimeMillis() < deadline) {
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
                    if (partial != null && streamIdForChunks != null) {
                        String chunk = cleanShellOutput(
                                partial.replace(begin, "").replace(end, ""));
                        if (chunk.length() > 16000) {
                            chunk = chunk.substring(0, 16000) + "\n[вывод обрезан]";
                        }
                        if (!chunk.equals(lastChunk)) {
                            lastChunk = chunk;
                            final String cOut = chunk;
                            ui(() -> { Listener l = listener; if (l != null) l.onExecChunk(streamIdForChunks, cOut); });
                        }
                    }
                }

                boolean preempted = (myTicket != execTicket);
                boolean timedOut = (endAt < 0 && running && process == proc && !preempted);
                if (timedOut || preempted) {
                    try {
                        synchronized (writeLock) {
                            proc.getOutputStream().write(new byte[]{3, 4, '\n'});
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
                if (endAt >= 0) {
                    shellReady = true;
                }
                if (timedOut) {
                    output = (output.isEmpty() ? "" : output + "\n")
                            + "[таймаут выполнения команды]";
                }
                if (output.length() > 16000) {
                    output = output.substring(0, 16000) + "\n[вывод обрезан]";
                }
                return new ExecOutcome(output, code, preempted);
            } catch (Exception e) {
                String errMsg = e.getMessage() == null ? "exec error" : e.getMessage();
                return new ExecOutcome(errMsg, -1, false);
            }
        }
    }

    void exec(String id, String command) {
        final Process proc = process;
        if (!running || proc == null) {
            ui(() -> { Listener l = listener; if (l != null) l.onExecResult(id, "Песочница не запущена", -1); });
            return;
        }
        String cmdText = command == null ? "" : command;
        boolean longPkg = cmdText.contains("install-base")
                || cmdText.contains("apt-get")
                || cmdText.contains("apt ")
                || cmdText.contains("pip ")
                || cmdText.contains("pip3 ")
                || cmdText.contains("npm ");
        long timeoutMs = longPkg ? 900_000L : 120_000L;
        new Thread(() -> {
            ExecOutcome outcome = runBlockingExec(id, cmdText, timeoutMs, id);
            if (outcome.preempted) return;
            ui(() -> { Listener l = listener; if (l != null) l.onExecResult(id, outcome.output, outcome.code); });
        }, "sandbox-exec").start();
    }

    private static String cleanShellOutput(String output) {
        if (output == null || output.isEmpty()) return "";
        String normalized = output.replace("\r\n", "\n").replace('\r', '\n');
        normalized = ANSI_PATTERN.matcher(normalized).replaceAll("");
        StringBuilder cleaned = new StringBuilder(normalized.length());
        for (String line : normalized.split("\n", -1)) {
            if (line.equals("#") || line.equals("# ")) continue;
            if (line.contains("__LOCALAPI_TOOLS__=") || line.contains("__LOCALAPI_BASE_V2__=")) continue;
            cleaned.append(line).append('\n');
        }
        return cleaned.toString().trim();
    }

    private static String buildGuestBootstrapScript(String utcDate) {
        String proxy = AppConfig.SANDBOX_GUEST_PROXY_URL;
        String proxyHost = AppConfig.SANDBOX_GUEST_PROXY_HOST;
        String proxyEnv = "export DEBIAN_FRONTEND=noninteractive\n"
                + "export APT_LISTCHANGES_FRONTEND=none\n"
                + "export http_proxy=" + proxy + "\n"
                + "export https_proxy=" + proxy + "\n"
                + "export HTTP_PROXY=" + proxy + "\n"
                + "export HTTPS_PROXY=" + proxy + "\n"
                + "export no_proxy=localhost,127.0.0.1," + proxyHost + "\n"
                + "export NO_PROXY=localhost,127.0.0.1," + proxyHost + "\n"
                + "export PATH=/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin\n";

        return "/bin/busybox ifconfig lo up 2>/dev/null\n"
                + "NIC=eth0; for d in /sys/class/net/*; do n=${d##*/}; [ \"$n\" = lo ] || { NIC=\"$n\"; break; }; done\n"
                + "/bin/busybox ifconfig \"$NIC\" 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null\n"
                + "/bin/busybox route add default gw " + proxyHost + " \"$NIC\" 2>/dev/null\n"
                + "printf 'nameserver 10.0.2.3\\n' > /etc/resolv.conf\n"
                + "date -u -s \"" + utcDate + "\" 2>/dev/null\n"
                + proxyEnv
                + "mkdir -p /usr/local/bin /etc/profile.d\n"
                + "for cmd in wget unzip tar ps top ping nslookup nc less vi; do "
                + "  if /bin/busybox --list 2>/dev/null | /bin/busybox grep -qx \"$cmd\"; then "
                + "    [ -e \"/usr/local/bin/$cmd\" ] || ln -s /bin/busybox \"/usr/local/bin/$cmd\" 2>/dev/null; "
                + "  fi; "
                + "done\n"
                + "cat << 'PROEOF' > /etc/profile.d/localapi.sh\n"
                + proxyEnv
                + "PROEOF\n"
                + "cat << 'APTEOF' > /etc/apt/apt.conf.d/99proxy\n"
                + "Acquire::http::Proxy \"" + proxy + "\";\n"
                + "Acquire::https::Proxy \"" + proxy + "\";\n"
                + "Acquire::http::Pipeline-Depth \"0\";\n"
                + "Acquire::ForceIPv4 \"true\";\n"
                + "Acquire::Retries \"3\";\n"
                + "Acquire::http::Timeout \"30\";\n"
                + "Acquire::https::Timeout \"30\";\n"
                + "DPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n"
                + "APTEOF\n"
                + "cat << 'IBEOF' > /usr/bin/install-base\n"
                + "#!/bin/sh\n"
                + "set -e\n"
                + proxyEnv
                + "echo '==> Обновление индексов apt...'\n"
                + "apt-get update\n"
                + "echo '==> Установка python3, pip, curl, wget, htop, git, jq, unzip, nano, tree, sqlite3...'\n"
                + "apt-get install -y --no-install-recommends ca-certificates curl wget python3 python3-pip python3-venv git htop procps jq unzip zip nano file tree sqlite3 dnsutils iputils-ping net-tools\n"
                + "touch /var/lib/localapi-base-v2\n"
                + "echo '==> Готово! Пакеты сохранены в rootfs.img.'\n"
                + "IBEOF\n"
                + "chmod +x /usr/bin/install-base\n"
                + "cd /workspace 2>/dev/null || true\n"
                + "__tools=\"\"; for t in python3 pip3 curl wget htop git jq unzip zip nano tree sqlite3; do "
                + "  [ -x \"/usr/bin/$t\" ] || [ -x \"/bin/$t\" ] && __tools=\"${__tools}${__tools:+,}$t\"; "
                + "done\n"
                + "printf '%s%s=%s\\n' '__LOCALAPI_' 'TOOLS__' \"$__tools\"\n"
                + "if [ -f /var/lib/localapi-base-v2 ]; then printf '%s%s=1\\n' '__LOCALAPI_' 'BASE_V2__'; else printf '%s%s=0\\n' '__LOCALAPI_' 'BASE_V2__'; fi\n"
                + "printf '%s%s\\n' '__LOCALAPI_' 'NET_READY__'\n";
    }

    private void maybeConfigureNetwork(Process proc) {
        if (networkConfigured || proc == null) return;
        synchronized (serialLock) {
            int len = serial.length();
            String rawTail = len > 64 ? serial.substring(len - 64) : serial.toString();
            String tail = ANSI_PATTERN.matcher(rawTail).replaceAll("").replace("\r", "");
            if (!tail.endsWith("# ") && !tail.endsWith("#")) return;
            if (serial.indexOf("Run /init") < 0 && serial.indexOf("can't access tty") < 0
                    && len < 1024) {
                return;
            }
            if (networkConfigured) return;
            networkConfigured = true;
        }
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String setup = buildGuestBootstrapScript(utc.format(new Date()));
        try {
            synchronized (writeLock) {
                writeUartSafe(proc.getOutputStream(), setup.getBytes(StandardCharsets.UTF_8));
            }
            log("sandbox network & apt configured (slirp 10.0.2.15 -> proxy "
                    + AppConfig.SANDBOX_GUEST_PROXY_URL + ")");
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
            installingBase = false;
        }
        if (p != null) p.destroy();
        SandboxProxy.stop();
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
                writeUartSafe(p.getOutputStream(), text.getBytes(StandardCharsets.UTF_8));
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
        if (total > AppConfig.SANDBOX_MAX_DOWNLOAD_BYTES) {
            throw new IOException("Архив песочницы превышает допустимый размер");
        }
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long readTotal = 0;
            int read;
            int lastPercent = -1;
            while ((read = in.read(buffer)) > 0) {
                readTotal += read;
                if (readTotal > AppConfig.SANDBOX_MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Превышен лимит размера загрузки песочницы");
                }
                out.write(buffer, 0, read);
                if (total > 0) {
                    int percent = (int) Math.min(100, readTotal * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int pct = percent;
                        ui(() -> { Listener l = listener; if (l != null) l.onProgress(pct); });
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

    static String tail(File file, int max) {
        if (file == null || !file.isFile() || max <= 0) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            if (length <= 0) return "";
            long skip = Math.max(0L, length - max);
            long skipped = 0L;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            int want = (int) Math.min(max, Math.max(0L, length - skipped));
            if (want == 0) return "";
            byte[] data = new byte[want];
            int offset = 0;
            while (offset < want) {
                int read = in.read(data, offset, want - offset);
                if (read < 0) break;
                offset += read;
            }
            return offset > 0 ? new String(data, 0, offset, StandardCharsets.UTF_8) : "";
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
}
