package dev.localapi.deepseek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Optional public Storage Access Framework workspace. The user picks a tree
 * once (for example Documents/LocalApiDeepSeek); each agent chat is a
 * subfolder in it, so ZIPs and project files are visible in a system file
 * manager. The private AgentWorkspace remains the fallback when no tree is
 * configured.
 */
final class SafAgentWorkspace {
    private static final String PREFS = "agent_saf";
    private static final String KEY_ROOT = "root_uri";
    private static final String KEY_CHAT_PREFIX = "chat_";
    private static final long MAX_ZIP_BYTES = 40L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 120L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 600;
    private static final int MAX_LISTED = 300;

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
            // Some providers do not offer persistable grants; the URI may still
            // survive while the activity stays alive, but info() will report
            // the root as unavailable after a restart.
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            throw new IOException("Система не дала доступ к выбранной папке");
        }
        verifyWritable(context, root);
        prefs(context).edit().putString(KEY_ROOT, uri.toString()).apply();
    }

    static JSONObject info(Context context) throws JSONException {
        Uri uri = rootUri(context);
        DocumentFile root = uri == null ? null : DocumentFile.fromTreeUri(context, uri);
        boolean configured = root != null && root.exists() && root.isDirectory();
        JSONObject out = new JSONObject();
        out.put("configured", configured);
        out.put("name", configured && root.getName() != null ? root.getName() : "");
        out.put("uri", uri == null ? "" : uri.toString());
        return out;
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
                DocumentFile existing = DocumentFile.fromSingleUri(context, Uri.parse(saved));
                if (existing != null && existing.exists() && existing.isDirectory()) {
                    return existing;
                }
            } catch (Exception ignored) {
            }
        }
        DocumentFile root = root(context);
        String name = folderName(chatId, title);
        DocumentFile dir = root.findFile(name);
        boolean created = false;
        if (dir == null && create) {
            dir = root.createDirectory(name);
            created = true;
            if (dir == null) dir = root.findFile(name);
        }
        if (dir == null || !dir.isDirectory()) {
            if (!create) return null;
            throw new IOException("Не удалось создать папку проекта");
        }
        prefs(context).edit().putString(key, dir.getUri().toString()).apply();
        if (created) migrateInternal(context, chatId, dir);
        return dir;
    }

    /**
     * One-time move of files that were imported before the SAF root was
     * selected. The private copy is left in place; the public one is the live
     * workspace from now on.
     */
    private static void migrateInternal(Context context, String chatId, DocumentFile dir) {
        try {
            File source = AgentWorkspace.root(context, chatId);
            if (!source.isDirectory()) return;
            long[] total = {0};
            copyInternalTree(context, source, source, dir, total);
        } catch (Exception ignored) {
            // The public folder remains usable even if migration is incomplete.
        }
    }

    private static void copyInternalTree(Context context, File root, File file, DocumentFile dir, long[] total) {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                DocumentFile next = ensureDirQuiet(dir, name);
                if (next != null) copyInternalTree(context, root, child, next, total);
            } else if (child.isFile()) {
                if (total[0] > MAX_TOTAL_BYTES) return;
                try (InputStream in = new FileInputStream(child)) {
                    DocumentFile target = dir.findFile(name);
                    if (target != null && target.isDirectory()) continue;
                    if (target == null) {
                        target = dir.createFile("application/octet-stream", name);
                    }
                    if (target == null) continue;
                    OutputStream out = context.getContentResolver()
                            .openOutputStream(target.getUri(), "wt");
                    if (out == null) continue;
                    try (OutputStream sink = out) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            total[0] += read;
                            if (total[0] > MAX_TOTAL_BYTES) return;
                            sink.write(buffer, 0, read);
                        }
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

    static JSONArray list(Context context, String chatId, String title) throws IOException {
        DocumentFile dir = chatDir(context, chatId, title, false);
        JSONArray out = new JSONArray();
        if (dir != null) collect(dir, "", out);
        return out;
    }

    private static void collect(DocumentFile dir, String prefix, JSONArray out) {
        if (out.length() >= MAX_LISTED) return;
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(
                f -> f.getName() == null ? "" : f.getName(),
                String.CASE_INSENSITIVE_ORDER));
        for (DocumentFile file : files) {
            if (out.length() >= MAX_LISTED) return;
            String name = file.getName();
            if (name == null || name.isEmpty()) continue;
            String relative = prefix.isEmpty() ? name : prefix + "/" + name;
            if (file.isDirectory()) {
                collect(file, relative, out);
            } else if (file.isFile()) {
                try {
                    JSONObject item = new JSONObject();
                    item.put("name", relative);
                    item.put("size", file.length());
                    out.put(item);
                } catch (JSONException ignored) {
                }
            }
        }
    }

    static JSONObject read(Context context, String chatId, String title,
            String name, int maxBytes) throws IOException, JSONException {
        DocumentFile file = find(context, chatId, title, name);
        if (file == null || !file.isFile()) throw new IOException("Файл не найден");
        int limit = Math.max(1, Math.min(maxBytes, 128 * 1024));
        long size = file.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        InputStream source = context.getContentResolver().openInputStream(file.getUri());
        if (source == null) throw new IOException("Не удалось открыть файл");
        try (InputStream in = source) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                int room = limit - out.size();
                if (room <= 0) {
                    truncated = true;
                    break;
                }
                if (read > room) {
                    out.write(buffer, 0, room);
                    truncated = true;
                    break;
                }
                out.write(buffer, 0, read);
            }
        }
        byte[] bytes = out.toByteArray();
        boolean binary = isBinary(bytes);
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("size", size);
        item.put("truncated", truncated || size > bytes.length);
        item.put("binary", binary);
        item.put("text", binary ? "" : new String(bytes, StandardCharsets.UTF_8));
        return item;
    }

    private static boolean isBinary(byte[] bytes) {
        int control = 0;
        int checked = Math.min(bytes.length, 8192);
        for (int i = 0; i < checked; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) return true;
            if (value < 7 || (value > 13 && value < 32)) control++;
        }
        return checked > 0 && control * 100 / checked > 5;
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
            out.write(1);
        } catch (Exception e) {
            probe.delete();
            throw new IOException("В выбранную папку нельзя записать файлы. "
                    + "Выберите папку в памяти устройства", e);
        }
        probe.delete();
    }

    static String importZip(Context context, String chatId, String title,
            Uri uri) throws IOException {
        DocumentFile dir = chatDir(context, chatId, title, true);
        File temp = new File(context.getCacheDir(), "agent-saf-" + System.nanoTime() + ".zip");
        try {
            long compressed = copyLimited(context, uri, temp, MAX_ZIP_BYTES);
            if (compressed <= 0) throw new IOException("ZIP пустой");
            int entries = 0;
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            verifyWritable(context, dir);
            try (ZipInputStream zin = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(temp)))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ENTRIES) {
                        throw new IOException("В ZIP слишком много файлов");
                    }
                    String rawName = entry.getName();
                    if (!safeRelative(rawName)) {
                        throw new IOException("Небезопасный путь в ZIP: " + rawName);
                    }
                    String cleanName = rawName.replace('\\', '/');
                    while (cleanName.endsWith("/")) {
                        cleanName = cleanName.substring(0, cleanName.length() - 1);
                    }
                    if (cleanName.isEmpty()) continue;
                    java.util.List<String> parts = new java.util.ArrayList<>();
                    for (String part : cleanName.split("/")) {
                        if (part.isEmpty() || part.equals(".")) continue;
                        parts.add(part);
                    }
                    if (parts.isEmpty()) continue;
                    DocumentFile parent = dir;
                    for (int i = 0; i < parts.size() - 1; i++) {
                        parent = ensureDir(parent, parts.get(i));
                    }
                    String leaf = parts.get(parts.size() - 1);
                    if (entry.isDirectory()) {
                        ensureDir(parent, leaf);
                    } else {
                        long fileBytes = 0;
                        DocumentFile target = parent.findFile(leaf);
                        if (target != null && target.isDirectory()) {
                            throw new IOException("Конфликт имени: " + cleanName);
                        }
                        if (target == null) {
                            target = parent.createFile("application/octet-stream", leaf);
                        }
                        if (target == null) {
                            throw new IOException("Не удалось создать " + cleanName);
                        }
                        OutputStream output;
                        try {
                            output = context.getContentResolver()
                                    .openOutputStream(target.getUri(), "wt");
                        } catch (Exception first) {
                            output = context.getContentResolver()
                                    .openOutputStream(target.getUri(), "w");
                        }
                        if (output == null) throw new IOException("Нет записи " + cleanName);
                        try (OutputStream out = output) {
                            int read;
                            while ((read = zin.read(buffer)) > 0) {
                                fileBytes += read;
                                total += read;
                                if (fileBytes > MAX_FILE_BYTES) {
                                    throw new IOException("Файл больше 32 МБ: " + cleanName);
                                }
                                if (total > MAX_TOTAL_BYTES) {
                                    throw new IOException("Распакованный ZIP больше 120 МБ");
                                }
                                out.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
            return entries + " файлов распаковано";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static DocumentFile ensureDir(DocumentFile parent, String name) throws IOException {
        DocumentFile dir = parent.findFile(name);
        if (dir != null && dir.isDirectory()) return dir;
        if (dir != null) throw new IOException("Конфликт имени: " + name);
        dir = parent.createDirectory(name);
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("Не удалось создать папку " + name);
        }
        return dir;
    }

    private static long copyLimited(Context context, Uri uri, File target, long limit)
            throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Не удалось открыть ZIP");
        long total = 0;
        try (InputStream source = in; OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = source.read(buffer)) > 0) {
                total += read;
                if (total > limit) throw new IOException("ZIP больше 40 МБ");
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    static boolean delete(Context context, String chatId, String title, String name) {
        if (!safeRelative(name)) return false;
        try {
            DocumentFile file = find(context, chatId, title, name);
            return file != null && deleteRecursive(file);
        } catch (IOException e) {
            return false;
        }
    }

    static void clear(Context context, String chatId, String title) throws IOException {
        DocumentFile dir = chatDir(context, chatId, title, false);
        if (dir == null) return;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            deleteRecursive(child);
        }
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

    static boolean renameChatDir(Context context, String chatId, String title) {
        try {
            DocumentFile dir = chatDir(context, chatId, title, false);
            if (dir == null) return false;
            String name = folderName(chatId, title);
            if (name.equals(dir.getName())) return true;
            return dir.renameTo(name);
        } catch (Exception e) {
            return false;
        }
    }

    private static DocumentFile find(Context context, String chatId, String title, String name)
            throws IOException {
        if (!safeRelative(name)) return null;
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

    private static boolean safeRelative(String name) {
        if (name == null || name.isEmpty()) return false;
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) return false;
        for (String part : normalized.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals("..")) return false;
        }
        return true;
    }
}
