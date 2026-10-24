package com.xiaocan.sentinel;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 微型本地 HTTP 服务: 从 assets/h5 提供文件, 解决 WebView file:// 下 ES Module 的 CORS 限制 */
public class LocalHttpServer {

    public final int port;
    private final AssetManager am;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = true;
    private ServerSocket server;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("mjs", "text/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("webp", "image/webp");
    }

    public LocalHttpServer(Context ctx) throws IOException {
        this.am = ctx.getAssets();
        server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
    }

    public String baseUrl() { return "http://127.0.0.1:" + port + "/index.html"; }

    public void start() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (running) { /* accept 异常, 继续尝试 */ }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { server.close(); } catch (Exception ignore) { }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || !requestLine.startsWith("GET")) { s.close(); return; }
            String path = requestLine.split(" ")[1];
            if (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty() || path.equals("/")) path = "index.html";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            byte[] body = readAsset("h5/" + path);
            OutputStream out = s.getOutputStream();
            if (body == null) {
                out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            } else {
                String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase() : "";
                String mime = MIME.getOrDefault(ext, "application/octet-stream");
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + body.length
                        + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes());
                out.write(body);
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignore) { }
        }
    }

    private byte[] readAsset(String path) throws IOException {
        InputStream in = null;
        try {
            in = am.open(path);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) { }
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 4096) break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
