package dev.localapi.deepseek;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Per-agent-chat workspace mounted directly into QEMU over 9p as /workspace.
 * All relative paths are canonical-checked to stay inside the workspace root.
 */
final class AgentWorkspace {
    static final long MAX_ZIP_BYTES = 40L * 1024 * 1024;
    static final int MAX_READ_BYTES = 128 * 1024;
    static final int MAX_MEDIA_BYTES = 16 * 1024 * 1024;
    static final int MAX_LISTED = 300;

    static final class FileEntry {
        final String name;
        final long size;

        FileEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    static final class TextFile {
        final String name;
        final long size;
        final boolean truncated;
        final boolean binary;
        final String text;

        TextFile(String name, long size, boolean truncated, boolean binary, String text) {
            this.name = name;
            this.size = size;
            this.truncated = truncated;
            this.binary = binary;
            this.text = text;
        }
    }

    static final class MediaBytes {
        final String name;
        final long size;
        final String mime;
        final byte[] bytes;

        MediaBytes(String name, long size, String mime, byte[] bytes) {
            this.name = name;
            this.size = size;
            this.mime = mime;
            this.bytes = bytes;
        }
    }

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
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "project.zip";
        }
        if (name.length() > 96) name = name.substring(name.length() - 96);
        if (!name.toLowerCase(Locale.US).endsWith(".zip")) name += ".zip";
        if (name.equals(".zip")) name = "project.zip";

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
            if (!copied) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
        try (InputStream check = new FileInputStream(target)) {
            int b0 = check.read();
            int b1 = check.read();
            if (b0 != 'P' || b1 != 'K') {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                throw new IOException("Выбранный файл не является ZIP-архивом");
            }
        }
        return name;
    }

    static List<FileEntry> listEntries(Context context, String chatId) {
        File root = root(context, chatId);
        List<FileEntry> out = new ArrayList<>();
        collect(root, "", out);
        return out;
    }

    private static void collect(File dir, String prefix, List<FileEntry> out) {
        if (out.size() >= MAX_LISTED) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (out.size() >= MAX_LISTED) return;
            String name = file.getName();
            String relative = prefix.isEmpty() ? name : prefix + "/" + name;
            if (file.isDirectory()) {
                collect(file, relative, out);
            } else if (file.isFile()) {
                out.add(new FileEntry(relative, file.length()));
            }
        }
    }

    static TextFile readText(Context context, String chatId, String name, int maxBytes)
            throws IOException {
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
        int limit = Math.max(1, Math.min(maxBytes, MAX_READ_BYTES));
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
        int textLen = truncated && !binary ? utf8SafeLength(bytes) : bytes.length;
        return new TextFile(
                name,
                size,
                truncated || size > bytes.length,
                binary,
                binary ? "" : new String(bytes, 0, textLen, StandardCharsets.UTF_8)
        );
    }

    private static int utf8SafeLength(byte[] bytes) {
        int len = bytes.length;
        if (len == 0) return 0;
        int i = len - 1;
        int cont = 0;
        while (i >= 0 && cont < 3 && (bytes[i] & 0xC0) == 0x80) {
            cont++;
            i--;
        }
        if (i < 0) return len;
        int lead = bytes[i] & 0xFF;
        int expected;
        if ((lead & 0x80) == 0) expected = 1;
        else if ((lead & 0xE0) == 0xC0) expected = 2;
        else if ((lead & 0xF0) == 0xE0) expected = 3;
        else if ((lead & 0xF8) == 0xF0) expected = 4;
        else return len;
        return (len - i) < expected ? i : len;
    }

    static MediaBytes readMediaBytes(Context context, String chatId, String name, int maxBytes)
            throws IOException {
        if (!safeRelative(name)) throw new IOException("Некорректный путь файла");
        File root = root(context, chatId);
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath + File.separator) || !target.isFile()) {
            throw new IOException("Файл не найден");
        }
        int limit = Math.max(1, Math.min(maxBytes, MAX_MEDIA_BYTES));
        long size = target.length();
        if (size > limit) throw new IOException("Файл больше " + (limit / (1024 * 1024)) + " МБ");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(32, size));
        try (InputStream in = new FileInputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        byte[] bytes = out.toByteArray();
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime == null) mime = mimeFromExtension(name);
        return new MediaBytes(name, size, mime, bytes);
    }

    private static String mimeFromExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".avif")) return "image/avif";
        return "application/octet-stream";
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

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    static boolean safeRelative(String name) {
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
