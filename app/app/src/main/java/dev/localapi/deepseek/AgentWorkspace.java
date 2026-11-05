package dev.localapi.deepseek;

import android.content.Context;
import android.net.Uri;

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
 * Per-agent-chat workspace. ZIP imports are treated as untrusted input:
 * names must stay inside the workspace, entries and sizes are capped, and
 * symlinks are never created by ZipInputStream.
 *
 * The workspace is deliberately separate from the Rust engine's files so a
 * later sandbox/runtime can be given exactly this directory.
 */
final class AgentWorkspace {
    private static final long MAX_ZIP_BYTES = 40L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 120L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 600;
    private static final int MAX_LISTED = 300;

    private AgentWorkspace() {
    }

    static File root(Context context, String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Некорректный идентификатор чата");
        }
        File root = new File(new File(context.getFilesDir(), "agent"),
                chatId + File.separator + "workspace");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Не удалось создать workspace");
        }
        return root;
    }

    static String copyZip(Context context, String chatId, Uri uri) throws IOException {
        File root = root(context, chatId);
        String name = "project.zip";
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String candidate = cursor.getString(index);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        name = candidate.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        name = name.replace('/', '_').replace('\\', '_').replace('\0', '_');
        if (name.length() > 96) name = name.substring(name.length() - 96);
        if (!name.toLowerCase().endsWith(".zip")) name += ".zip";
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Некорректное имя ZIP");
        }
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Не удалось открыть ZIP");
        long total = 0;
        boolean copied = false;
        try (InputStream source = in; OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = source.read(buffer)) > 0) {
                total += read;
                if (total > MAX_ZIP_BYTES) throw new IOException("ZIP больше 40 МБ");
                out.write(buffer, 0, read);
            }
            copied = true;
        } finally {
            if (!copied) target.delete();
        }
        try (InputStream check = new FileInputStream(target)) {
            byte[] signature = new byte[4];
            int count = check.read(signature);
            if (count < 2 || signature[0] != 'P' || signature[1] != 'K') {
                target.delete();
                throw new IOException("Выбранный файл не является ZIP");
            }
        }
        return name;
    }

    static JSONArray list(Context context, String chatId) {
        File root = root(context, chatId);
        JSONArray out = new JSONArray();
        collect(root, root, out);
        return out;
    }

    private static void collect(File root, File dir, JSONArray out) {
        if (out.length() >= MAX_LISTED) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (out.length() >= MAX_LISTED) return;
            if (file.isDirectory()) {
                collect(root, file, out);
            } else if (file.isFile()) {
                try {
                    JSONObject item = new JSONObject();
                    String relative = root.toPath().relativize(file.toPath()).toString();
                    item.put("name", relative.replace(File.separatorChar, '/'));
                    item.put("size", file.length());
                    out.put(item);
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String importZip(Context context, String chatId, Uri uri) throws IOException {
        File root = root(context, chatId);
        File temp = new File(context.getCacheDir(), "agent-import-" + System.nanoTime() + ".zip");
        long compressed;
        try {
            compressed = copyLimited(context, uri, temp, MAX_ZIP_BYTES);
            if (compressed <= 0) {
                throw new IOException("ZIP пустой");
            }
            int entries = 0;
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            String rootPath = root.getCanonicalPath();
            try (ZipInputStream zin = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(temp)))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ENTRIES) {
                        throw new IOException("В ZIP слишком много файлов");
                    }
                    String name = entry.getName();
                    if (!safeRelative(name)) {
                        throw new IOException("Небезопасный путь в ZIP: " + name);
                    }
                    File target = new File(root, name);
                    String targetPath = target.getCanonicalPath();
                    if (!targetPath.equals(rootPath)
                            && !targetPath.startsWith(rootPath + File.separator)) {
                        throw new IOException("Путь выходит за пределы workspace");
                    }
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs()) {
                            throw new IOException("Не удалось создать папку " + name);
                        }
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Не удалось создать папку для " + name);
                    }
                    long fileBytes = 0;
                    try (OutputStream out = new FileOutputStream(target)) {
                        int read;
                        while ((read = zin.read(buffer)) > 0) {
                            fileBytes += read;
                            total += read;
                            if (fileBytes > MAX_FILE_BYTES) {
                                throw new IOException("Файл больше 32 МБ: " + name);
                            }
                            if (total > MAX_TOTAL_BYTES) {
                                throw new IOException("Распакованный ZIP больше 120 МБ");
                            }
                            out.write(buffer, 0, read);
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

    static JSONObject read(Context context, String chatId, String name, int maxBytes)
            throws IOException, JSONException {
        if (!safeRelative(name)) {
            throw new IOException("Некорректный путь файла");
        }
        File root = root(context, chatId);
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath + File.separator) || !target.isFile()) {
            throw new IOException("Файл не найден");
        }
        int limit = Math.max(1, Math.min(maxBytes, 64 * 1024));
        long size = target.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream in = new FileInputStream(target)) {
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

    static boolean delete(Context context, String chatId, String name) {
        if (!safeRelative(name)) return false;
        File root = root(context, chatId);
        try {
            File target = new File(root, name);
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.startsWith(rootPath + File.separator)) return false;
            return deleteRecursive(target);
        } catch (IOException e) {
            return false;
        }
    }

    static void clear(Context context, String chatId) {
        File root = root(context, chatId);
        deleteRecursive(root);
    }

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
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
                if (total > limit) {
                    throw new IOException("ZIP больше 40 МБ");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
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
