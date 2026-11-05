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
 * Tiny HTTP proxy for the QEMU guest. Native slirp can be defeated by an
 * Android VPN's fake-IP DNS (TCP connects to 198.18.x.x but no HTTP data).
 * Java's HttpURLConnection uses the app network stack, which is exactly the
 * path the working in-app browser uses. Guest 10.0.2.2:8888 reaches this
 * loopback listener; apt and busybox wget can use it for HTTP, while CONNECT
 * is tunnelled for clients that support HTTPS proxying.
 */
final class SandboxProxy {
    private static final String TAG = "SandboxProxy";
    private static final int PORT = 8888;
    private static final int MAX_BODY = 64 * 1024 * 1024;

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
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.add(new String[]{
                        line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim()
                });
            }
            if ("CONNECT".equalsIgnoreCase(method)) {
                tunnel(socket, in, out, target);
                return;
            }
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                respond(out, 400, "Bad Request", "text/plain", "absolute URL required".getBytes(StandardCharsets.UTF_8));
                return;
            }
            URL url = new URL(target);
            if (!"http".equalsIgnoreCase(url.getProtocol())) {
                respond(out, 400, "Bad Request", "text/plain", "use CONNECT for https".getBytes(StandardCharsets.UTF_8));
                return;
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (String[] h : headers) {
                String name = h[0];
                String lname = name.toLowerCase();
                if (lname.equals("proxy-connection") || lname.equals("connection")
                        || lname.equals("keep-alive") || lname.equals("host")
                        || lname.equals("proxy-authorization") || lname.equals("content-length")
                        || lname.equals("accept-encoding")) continue;
                connection.setRequestProperty(name, h[1]);
            }
            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            InputStream body = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] data = body == null ? new byte[0] : readLimited(body, MAX_BODY);
            String contentType = connection.getContentType();
            respond(out, code, message == null ? "" : message,
                    contentType == null ? "application/octet-stream" : contentType, data);
        } catch (Exception e) {
            Log.w(TAG, "request: " + e.getMessage());
        }
    }

    private static void tunnel(Socket client, InputStream clientIn, OutputStream clientOut,
                               String target) {
        int colon = target.lastIndexOf(':');
        if (colon <= 0) return;
        String host = target.substring(0, colon);
        int port;
        try { port = Integer.parseInt(target.substring(colon + 1)); }
        catch (Exception e) { return; }
        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), 15_000);
            remote.setSoTimeout(60_000);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            Thread up = new Thread(() -> copy(clientIn, remote), "proxy-up");
            up.setDaemon(true);
            up.start();
            copy(remote.getInputStream(), clientOut);
            try { up.join(1000); } catch (InterruptedException ignored) { }
        } catch (Exception e) {
            Log.w(TAG, "connect " + target + ": " + e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buffer = new byte[32 * 1024];
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static void copy(InputStream in, Socket out) {
        try {
            copy(in, out.getOutputStream());
            out.shutdownOutput();
        } catch (Exception ignored) { }
    }

    private static void respond(OutputStream out, int code, String message,
                                String contentType, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + message + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > max) throw new IOException("response too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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
