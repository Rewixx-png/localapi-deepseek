package dev.localapi.deepseek;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Local HTTP/CONNECT proxy for the QEMU guest (10.0.2.2:18991 -> 127.0.0.1:18991).
 * Routes guest traffic through Android's Java network stack so apt and CLI tools
 * keep working even when a VPN with fake-IP DNS breaks raw slirp TCP sockets.
 */
final class SandboxProxy {
    private static final String TAG = "SandboxProxy";
    private static final int PORT = 18991;
    private static final int MAX_REQUEST_BODY = 32 * 1024 * 1024;

    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean running;

    private SandboxProxy() { }

    static synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        } catch (Exception e) {
            Log.w(TAG, "listen failed: " + e.getMessage());
            return;
        }
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    Thread worker = new Thread(() -> handle(client), "sandbox-proxy-client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception e) {
                    if (running) Log.w(TAG, "accept: " + e.getMessage());
                }
            }
        }, "sandbox-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "proxy on 127.0.0.1:" + PORT);
    }

    static synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
    }

    private static void handle(Socket client) {
        HttpURLConnection connection = null;
        try (Socket socket = client) {
            socket.setSoTimeout(30_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String target = parts[1];

            List<String[]> headers = new ArrayList<>();
            long contentLength = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    headers.add(new String[]{name, val});
                    if ("content-length".equalsIgnoreCase(name)) {
                        try { contentLength = Long.parseLong(val); } catch (NumberFormatException ignored) { }
                    }
                }
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                tunnel(socket, in, out, target);
                return;
            }

            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                respondError(out, 400, "Bad Request", "absolute URL required");
                return;
            }
            URL url = new URL(target);
            if (!"http".equalsIgnoreCase(url.getProtocol())) {
                respondError(out, 400, "Bad Request", "use CONNECT for https");
                return;
            }
            if (contentLength > MAX_REQUEST_BODY) {
                respondError(out, 413, "Payload Too Large", "request body too large");
                return;
            }

            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (String[] h : headers) {
                String name = h[0];
                String lname = name.toLowerCase();
                if (lname.equals("proxy-connection") || lname.equals("connection")
                        || lname.equals("keep-alive") || lname.equals("host")
                        || lname.equals("proxy-authorization") || lname.equals("content-length")
                        || lname.equals("transfer-encoding") || lname.equals("accept-encoding")) continue;
                connection.setRequestProperty(name, h[1]);
            }

            if (contentLength > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(contentLength);
                try (OutputStream reqOut = connection.getOutputStream()) {
                    copyExact(in, reqOut, contentLength);
                }
            }

            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            String contentType = connection.getContentType();
            String location = connection.getHeaderField("Location");
            long respLen = connection.getContentLengthLong();
            InputStream body = code >= 400 ? connection.getErrorStream() : connection.getInputStream();

            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(' ')
                    .append(cleanHeader(message)).append("\r\n");
            head.append("Content-Type: ")
                    .append(contentType == null ? "application/octet-stream" : cleanHeader(contentType))
                    .append("\r\n");
            if (location != null && !location.isEmpty()) {
                head.append("Location: ").append(cleanHeader(location)).append("\r\n");
            }
            if (respLen >= 0) {
                head.append("Content-Length: ").append(respLen).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null && !"HEAD".equalsIgnoreCase(method)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = body.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void tunnel(Socket client, InputStream clientIn, OutputStream clientOut,
                               String target) {
        String host;
        int port;
        try {
            if (target.startsWith("[")) {
                int end = target.indexOf("]:");
                if (end <= 1) return;
                host = target.substring(1, end);
                port = Integer.parseInt(target.substring(end + 2));
            } else {
                int colon = target.lastIndexOf(':');
                if (colon <= 0) return;
                host = target.substring(0, colon);
                port = Integer.parseInt(target.substring(colon + 1));
            }
            if (host.isEmpty() || port <= 0 || port > 65535) return;
        } catch (Exception e) {
            return;
        }

        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), 15_000);
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            Thread up = new Thread(() -> copyToRemote(clientIn, remote), "proxy-up");
            up.setDaemon(true);
            up.start();
            copyStream(remote.getInputStream(), clientOut);
            try { up.join(1000); } catch (InterruptedException ignored) { }
        } catch (Exception e) {
            Log.w(TAG, "connect " + target + ": " + e.getMessage());
            try {
                respondError(clientOut, 502, "Bad Gateway", "connect failed");
            } catch (IOException ignored) { }
        }
    }

    private static void copyExact(InputStream in, OutputStream out, long remaining) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, want);
            if (read < 0) throw new IOException("unexpected EOF in request body");
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    private static void copyStream(InputStream in, OutputStream out) {
        byte[] buffer = new byte[32 * 1024];
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception ignored) { }
    }

    private static void copyToRemote(InputStream in, Socket out) {
        try {
            copyStream(in, out.getOutputStream());
            out.shutdownOutput();
        } catch (Exception ignored) { }
    }

    private static void respondError(OutputStream out, int code, String message,
                                     String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + message + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String cleanHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') out.write(value);
            if (out.size() > 16 * 1024) throw new IOException("header too large");
        }
        if (value < 0 && out.size() == 0) return null;
        return out.toString("ISO-8859-1");
    }
}
