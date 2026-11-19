package dev.localapi.deepseek;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP/1.1 + CONNECT proxy for the QEMU guest (10.0.2.2:18991 -> 127.0.0.1:18991).
 * Routes guest traffic through Android's Java network stack so apt, pip, curl,
 * wget and git keep working reliably even behind VPNs with fake-IP DNS.
 */
final class SandboxProxy {
    private static final String TAG = "SandboxProxy";
    static final int PORT = 18991;
    private static final int MAX_REQUEST_BODY = 32 * 1024 * 1024;
    private static final String[] FORWARD_RESP_HEADERS = {
            "Location", "Last-Modified", "ETag", "Content-Range",
            "Accept-Ranges", "Date", "Cache-Control", "Retry-After"
    };

    private static ServerSocket server;
    private static Thread acceptThread;
    private static ExecutorService pool;
    private static volatile boolean running;

    private SandboxProxy() { }

    static synchronized void start() {
        if (running && server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        } catch (Exception e) {
            Log.w(TAG, "listen failed: " + e.getMessage());
            return;
        }
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sandbox-proxy-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    ExecutorService exec = pool;
                    if (exec != null && !exec.isShutdown()) {
                        exec.execute(() -> handle(client));
                    } else {
                        client.close();
                    }
                } catch (Exception e) {
                    if (running) Log.w(TAG, "accept: " + e.getMessage());
                }
            }
        }, "sandbox-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "proxy listening on 127.0.0.1:" + PORT);
    }

    static synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** End-to-end diagnostic through the local proxy to ports.ubuntu.com. */
    static String selfTest() {
        start();
        if (!running) return "Прокси 127.0.0.1:" + PORT + " не запущен";
        long started = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 5_000);
            s.setSoTimeout(12_000);
            OutputStream out = s.getOutputStream();
            out.write(("HEAD http://ports.ubuntu.com/ubuntu-ports/ HTTP/1.1\r\n"
                    + "Host: ports.ubuntu.com\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String status = readLine(new BufferedInputStream(s.getInputStream()));
            long ms = System.currentTimeMillis() - started;
            if (status != null && status.startsWith("HTTP/")) {
                return "OK · " + status + " (" + ms + " мс)";
            }
            return "Пустой ответ прокси (" + ms + " мс)";
        } catch (Exception e) {
            return "Ошибка прокси: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void handle(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(45_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);

            // Handle HTTP/1.1 persistent connections cleanly while still closing
            // whenever a response has unknown length or the client asks to close.
            for (int reqCount = 0; reqCount < 64; reqCount++) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                String[] parts = requestLine.split(" ");
                if (parts.length < 3) return;
                String method = parts[0];
                String target = parts[1];

                List<String[]> headers = new ArrayList<>();
                long contentLength = -1;
                boolean clientWantsClose = false;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String name = line.substring(0, colon).trim();
                        String val = line.substring(colon + 1).trim();
                        headers.add(new String[]{name, val});
                        if ("content-length".equalsIgnoreCase(name)) {
                            try { contentLength = Long.parseLong(val); } catch (NumberFormatException ignored) { }
                        } else if ("connection".equalsIgnoreCase(name) || "proxy-connection".equalsIgnoreCase(name)) {
                            if (val.toLowerCase().contains("close")) clientWantsClose = true;
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

                boolean keepAlive = forwardHttp(url, method, headers, contentLength, clientWantsClose, in, out);
                if (!keepAlive) return;
            }
        } catch (Exception e) {
            Log.w(TAG, "request: " + e.getMessage());
        }
    }

    private static boolean forwardHttp(
            URL url,
            String method,
            List<String[]> headers,
            long contentLength,
            boolean clientWantsClose,
            InputStream clientIn,
            OutputStream clientOut
    ) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            // Let apt/curl/wget handle redirects directly (RFC 9110 proxy semantics).
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");

            for (String[] h : headers) {
                String name = h[0];
                String lname = name.toLowerCase();
                if (lname.equals("proxy-connection") || lname.equals("connection")
                        || lname.equals("keep-alive") || lname.equals("host")
                        || lname.equals("proxy-authorization") || lname.equals("content-length")
                        || lname.equals("transfer-encoding") || lname.equals("accept-encoding")) {
                    continue;
                }
                connection.setRequestProperty(name, h[1]);
            }

            if (contentLength > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(contentLength);
                try (OutputStream reqOut = connection.getOutputStream()) {
                    copyExact(clientIn, reqOut, contentLength);
                }
            }

            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            if (message == null || message.isEmpty()) message = "OK";
            String contentType = connection.getContentType();
            long respLen = connection.getContentLengthLong();
            boolean noBody = "HEAD".equalsIgnoreCase(method)
                    || code == 204 || code == 304 || (code >= 100 && code < 200);
            boolean canKeepAlive = !clientWantsClose && (noBody || respLen >= 0);

            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(' ')
                    .append(cleanHeader(message)).append("\r\n");
            if (contentType != null && !contentType.isEmpty()) {
                head.append("Content-Type: ").append(cleanHeader(contentType)).append("\r\n");
            } else if (!noBody) {
                head.append("Content-Type: application/octet-stream\r\n");
            }
            for (String headerName : FORWARD_RESP_HEADERS) {
                String val = connection.getHeaderField(headerName);
                if (val != null && !val.isEmpty()) {
                    head.append(headerName).append(": ").append(cleanHeader(val)).append("\r\n");
                }
            }
            if (respLen >= 0) {
                head.append("Content-Length: ").append(respLen).append("\r\n");
            } else if (noBody && code != 304 && code != 204) {
                head.append("Content-Length: 0\r\n");
            }
            head.append("Connection: ").append(canKeepAlive ? "keep-alive" : "close").append("\r\n\r\n");
            clientOut.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (!noBody) {
                InputStream body = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (body != null) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = body.read(buffer)) > 0) {
                        clientOut.write(buffer, 0, read);
                    }
                }
            }
            clientOut.flush();
            return canKeepAlive;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static InetAddress resolvePreferIpv4(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress addr : all) {
            if (addr instanceof Inet4Address) return addr;
        }
        if (all.length > 0) return all[0];
        throw new IOException("DNS lookup failed for " + host);
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
            remote.setTcpNoDelay(true);
            InetAddress addr = resolvePreferIpv4(host);
            remote.connect(new InetSocketAddress(addr, port), 15_000);
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();
            Thread up = new Thread(() -> copyToRemote(clientIn, remote), "proxy-up");
            up.setDaemon(true);
            up.start();
            copyStream(remote.getInputStream(), clientOut);
            try { client.shutdownOutput(); } catch (Exception ignored) { }
            try { up.join(1500); } catch (InterruptedException ignored) { }
        } catch (Exception e) {
            Log.w(TAG, "connect " + target + ": " + e.getMessage());
            try {
                respondError(clientOut, 502, "Bad Gateway", "connect failed: " + e.getMessage());
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
