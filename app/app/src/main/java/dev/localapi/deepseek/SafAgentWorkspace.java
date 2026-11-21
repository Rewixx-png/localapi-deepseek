package dev.localapi.deepseek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Optional public Storage Access Framework (SAF) workspace.
 *
 * Because QEMU's 9p `-virtfs` requires a real POSIX path (`AgentWorkspace`),
 * this class synchronizes files bidirectionally between the user-chosen SAF
 * tree folder and the private `AgentWorkspace` directory for each agent chat.
 */
final class SafAgentWorkspace {
    private static final String PREFS = "agent_saf";
    private static final String KEY_ROOT = "root_uri";
    private static final String KEY_CHAT_PREFIX = "chat_";
    private static final long MAX_TOTAL_BYTES = 120L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 40L * 1024 * 1024;
    private static final int MAX_SYNC_FILES = 300;

    static final class RootInfo {
        final boolean configured;
        final String name;
        final String uri;

        RootInfo(boolean configured, String name, String uri) {
            this.configured = configured;
            this.name = name;
            this.uri = uri;
        }
    }

    private SafAgentWorkspace() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri rootUri(Context context) {
        String raw = prefs(context).getString(KEY_ROOT, "");
        if (raw.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(raw);
            return "content".equals(uri.getScheme()) ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean hasRoot(Context context) {
        Uri uri = rootUri(context);
        if (uri == null) return false;
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        return root != null && root.exists() && root.isDirectory();
    }

    static void setRoot(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IllegalArgumentException("Нет папки");
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            throw new IOException("Система не дала доступ к выбранной папке");
        }
        verifyWritable(context, root);
        prefs(context).edit().putString(KEY_ROOT, uri.toString()).apply();
    }

    static void clearRoot(Context context) {
        prefs(context).edit().remove(KEY_ROOT).apply();
    }

    static RootInfo info(Context context) {
        Uri uri = rootUri(context);
        DocumentFile root = uri == null ? null : DocumentFile.fromTreeUri(context, uri);
        boolean configured = root != null && root.exists() && root.isDirectory();
        return new RootInfo(
                configured,
                configured && root.getName() != null ? root.getName() : "",
                uri == null ? "" : uri.toString()
        );
    }

    private static DocumentFile root(Context context) throws IOException {
        Uri uri = rootUri(context);
        if (uri == null) throw new IOException("Папка workspace не выбрана");
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            throw new IOException("Нет доступа к папке workspace");
        }
        return root;
    }

    private static String safeChatId(String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Некорректный идентификатор чата");
        }
        return chatId;
    }

    private static String sanitize(String title) {
        String clean = title == null ? "" : title.trim();
        clean = clean.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        clean = clean.replaceAll("\\s+", " ").trim();
        if (clean.length() > 48) clean = clean.substring(0, 48).trim();
        return clean.isEmpty() ? "project" : clean;
    }

    private static String shortId(String chatId) {
        String clean = chatId.replaceAll("[^A-Za-z0-9]", "");
        if (clean.length() <= 6) return clean;
        return clean.substring(clean.length() - 6);
    }

    private static String folderName(String chatId, String title) {
        return sanitize(title) + "-" + shortId(chatId);
    }

    static DocumentFile chatDir(Context context, String chatId, String title, boolean create)
            throws IOException {
        safeChatId(chatId);
        String key = KEY_CHAT_PREFIX + chatId;
        String saved = prefs(context).getString(key, "");
        if (!saved.isEmpty()) {
            try {
                DocumentFile existing = DocumentFile.fromTreeUri(context, Uri.parse(saved));
                if (existing != null && existing.exists() && existing.isDirectory()) {
                    return existing;
                }
            } catch (Exception ignored) {
            }
        }
        DocumentFile root = root(context);
        String name = folderName(chatId, title);
        DocumentFile dir = root.findFile(name);
        if (dir == null && create) {
            dir = root.createDirectory(name);
            if (dir == null) dir = root.findFile(name);
        }
        if (dir == null || !dir.isDirectory()) {
            if (!create) return null;
            throw new IOException("Не удалось создать папку проекта");
        }
        prefs(context).edit().putString(key, dir.getUri().toString()).apply();
        return dir;
    }

    /**
     * Pulls new or newer files from the user-visible SAF folder into the private
     * QEMU 9p workspace directory. Safe no-op when no SAF root is configured.
     */
    static void syncFromSaf(Context context, String chatId, String title) {
        if (!hasRoot(context) || chatId == null || chatId.isEmpty()) return;
        try {
            DocumentFile safDir = chatDir(context, chatId, title, false);
            if (safDir == null) return;
            File localRoot = AgentWorkspace.root(context, chatId);
            String rootCanonical = localRoot.getCanonicalPath();
            long[] totalBytes = {0L};
            int[] fileCount = {0};
            pullTree(context, safDir, localRoot, rootCanonical, totalBytes, fileCount);
        } catch (Exception ignored) {
        }
    }

    /**
     * Pushes new or newer files from the private QEMU 9p workspace directory
     * into the user-visible SAF folder. Safe no-op when no SAF root is configured.
     */
    static void syncToSaf(Context context, String chatId, String title) {
        if (!hasRoot(context) || chatId == null || chatId.isEmpty()) return;
        try {
            File localRoot = AgentWorkspace.root(context, chatId);
            if (!localRoot.isDirectory()) return;
            File[] top = localRoot.listFiles();
            if (top == null || top.length == 0) return;
            DocumentFile safDir = chatDir(context, chatId, title, true);
            if (safDir == null) return;
            long[] totalBytes = {0L};
            int[] fileCount = {0};
            pushTree(context, localRoot, safDir, totalBytes, fileCount);
        } catch (Exception ignored) {
        }
    }

    static boolean delete(Context context, String chatId, String title, String name) {
        if (!hasRoot(context) || !AgentWorkspace.safeRelative(name)) return false;
        try {
            DocumentFile file = find(context, chatId, title, name);
            return file != null && deleteRecursive(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static void pullTree(
            Context context,
            DocumentFile safDir,
            File localDir,
            String rootCanonical,
            long[] totalBytes,
            int[] fileCount
    ) {
        DocumentFile[] children = safDir.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            if (fileCount[0] >= MAX_SYNC_FILES || totalBytes[0] >= MAX_TOTAL_BYTES) return;
            String name = child.getName();
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
                continue;
            }
            File target = new File(localDir, name);
            String targetCanonical;
            try {
                targetCanonical = target.getCanonicalPath();
            } catch (IOException e) {
                continue;
            }
            if (!targetCanonical.startsWith(rootCanonical + File.separator)) continue;

            if (child.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs()) continue;
                pullTree(context, child, target, rootCanonical, totalBytes, fileCount);
            } else if (child.isFile()) {
                long len = child.length();
                if (len > MAX_FILE_BYTES || totalBytes[0] + len > MAX_TOTAL_BYTES) continue;
                long safMtime = child.lastModified();
                if (target.isFile() && target.length() == len
                        && (safMtime <= 0 || target.lastModified() >= safMtime - 1500)) {
                    fileCount[0]++;
                    totalBytes[0] += len;
                    continue;
                }
                try {
                    InputStream raw = context.getContentResolver().openInputStream(child.getUri());
                    if (raw == null) continue;
                    try (InputStream in = raw; OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[64 * 1024];
                        int read;
                        long copied = 0;
                        while ((read = in.read(buf)) > 0) {
                            copied += read;
                            if (copied > MAX_FILE_BYTES) break;
                            out.write(buf, 0, read);
                        }
                        totalBytes[0] += copied;
                        fileCount[0]++;
                    }
                    if (safMtime > 0) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setLastModified(safMtime);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void pushTree(
            Context context,
            File localDir,
            DocumentFile safDir,
            long[] totalBytes,
            int[] fileCount
    ) {
        File[] children = localDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (fileCount[0] >= MAX_SYNC_FILES || totalBytes[0] >= MAX_TOTAL_BYTES) return;
            String name = child.getName();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
            if (child.isDirectory()) {
                DocumentFile next = ensureDirQuiet(safDir, name);
                if (next != null) pushTree(context, child, next, totalBytes, fileCount);
            } else if (child.isFile()) {
                long len = child.length();
                if (len > MAX_FILE_BYTES || totalBytes[0] + len > MAX_TOTAL_BYTES) continue;
                DocumentFile existing = safDir.findFile(name);
                if (existing != null && existing.isDirectory()) continue;
                if (existing != null && existing.isFile() && existing.length() == len
                        && existing.lastModified() >= child.lastModified() - 1500) {
                    fileCount[0]++;
                    totalBytes[0] += len;
                    continue;
                }
                try {
                    DocumentFile target = existing != null
                            ? existing
                            : safDir.createFile("application/octet-stream", name);
                    if (target == null) continue;
                    OutputStream rawOut;
                    try {
                        rawOut = context.getContentResolver().openOutputStream(target.getUri(), "wt");
                    } catch (Exception first) {
                        rawOut = context.getContentResolver().openOutputStream(target.getUri(), "w");
                    }
                    if (rawOut == null) continue;
                    try (InputStream in = new FileInputStream(child); OutputStream out = rawOut) {
                        byte[] buf = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buf)) > 0) {
                            totalBytes[0] += read;
                            if (totalBytes[0] > MAX_TOTAL_BYTES) return;
                            out.write(buf, 0, read);
                        }
                        fileCount[0]++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static DocumentFile ensureDirQuiet(DocumentFile parent, String name) {
        DocumentFile dir = parent.findFile(name);
        if (dir != null && dir.isDirectory()) return dir;
        if (dir != null) return null;
        try {
            return parent.createDirectory(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static void verifyWritable(Context context, DocumentFile dir) throws IOException {
        String probeName = "write-test-" + System.nanoTime() + ".tmp";
        DocumentFile probe;
        try {
            probe = dir.createFile("application/octet-stream", probeName);
        } catch (UnsupportedOperationException e) {
            throw new IOException("Выбранная папка не поддерживает создание файлов. "
                    + "Выберите папку в памяти устройства (например, Documents)", e);
        } catch (Exception e) {
            throw new IOException("Не удалось создать файл в выбранной папке. "
                    + "Выберите папку в памяти устройства", e);
        }
        if (probe == null) {
            throw new IOException("Выбранная папка только для чтения. Выберите папку "
                    + "в памяти устройства (например, Documents)");
        }
        try (OutputStream out = context.getContentResolver()
                .openOutputStream(probe.getUri(), "wt")) {
            if (out == null) throw new IOException("no output stream");
            out.write(1);
        } catch (Exception e) {
            probe.delete();
            throw new IOException("В выбранную папку нельзя записать файлы. "
                    + "Выберите папку в памяти устройства", e);
        }
        probe.delete();
    }

    private static boolean deleteRecursive(DocumentFile file) {
        if (file.isDirectory()) {
            DocumentFile[] children = file.listFiles();
            if (children != null) {
                for (DocumentFile child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private static DocumentFile find(Context context, String chatId, String title, String name)
            throws IOException {
        if (!AgentWorkspace.safeRelative(name)) return null;
        DocumentFile current = chatDir(context, chatId, title, false);
        if (current == null) return null;
        for (String part : name.split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile next = current.findFile(part);
            if (next == null) return null;
            current = next;
        }
        return current;
    }
}
