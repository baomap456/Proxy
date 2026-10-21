package com.proxy;

import java.io.BufferedInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.proxy.content.ContentInspector;
import com.proxy.dto.LogEntry;
import com.proxy.filter.BlockPageGenerator;
import com.proxy.http.HttpParseException;
import com.proxy.http.HttpRequest;
import com.proxy.http.HttpRequestParser;
import com.proxy.http.HttpResponse;
import com.proxy.http.HttpResponseParser;
import com.proxy.log.LogClient;
import com.proxy.policy.Decision;
import com.proxy.policy.PolicyEngine;

/**
 * Xử lý 1 kết nối client:
 * parse request -> pha 1 (policy theo request) -> BLOCK / tunnel (CONNECT) / forward (HTTP)
 * -> pha 2 với HTTP (quét nội dung hoặc cách ly) -> ghi log.
 */
public class ClientHandler implements Runnable {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final int TUNNEL_IDLE_TIMEOUT_MS = 120_000;
    private static final int TUNNEL_CLOSE_GRACE_MS = 2000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final byte[] ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket clientSocket;
    private final PolicyEngine engine;
    private final LogClient logClient;
    private final String clientIp;

    public ClientHandler(Socket clientSocket, PolicyEngine engine, LogClient logClient) {
        this.clientSocket = clientSocket;
        this.engine = engine;
        this.logClient = logClient;
        this.clientIp = clientSocket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            HttpRequest request;
            try {
                request = HttpRequestParser.parse(clientIn);
            } catch (HttpParseException e) {
                System.err.println("[BAD REQUEST] " + clientIp + " -> " + e.getStatus() + ": " + e.getMessage());
                sendError(clientOut, e.getStatus());
                return;
            } catch (SocketTimeoutException e) {
                return;   // trình duyệt mở sẵn kết nối rồi không gửi gì -> đóng lặng lẽ
            }
            if (request == null) return;

            System.out.println("[REQUEST] " + clientIp + " " + request);

            String url = request.isConnectMethod() ? "" : request.getUrl();
            Decision decision = engine.evaluate(clientIp, request.getHost(), url);

            if (decision.isBlocked()) {
                System.out.println("[BLOCK] " + request.getHost() + " <- rule #" + decision.ruleId());
                log(request, decision, 403);
                clientOut.write(BlockPageGenerator.build(request, decision));
                clientOut.flush();
            } else if (request.isConnectMethod() && decision.isIsolated()) {
                // HTTPS đã mã hóa: proxy không đọc được nội dung để làm sạch -> chặn
                System.out.println("[ISOLATE->BLOCK] " + request.getHost() + " (HTTPS không cách ly được)");
                log(request, decision, 403);
                clientOut.write(BlockPageGenerator.build(request, decision,
                        "Trang thuộc diện cách ly nhưng kết nối HTTPS không thể cách ly nên bị chặn"));
                clientOut.flush();
            } else if (request.isConnectMethod()) {
                handleConnectTunnel(request, decision, clientIn, clientOut);
            } else {
                handlePlainHttp(request, decision, clientOut);
            }

        } catch (IOException e) {
            System.err.println("[ERROR] " + clientIp + ": " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[BUG] Lỗi không mong đợi khi xử lý " + clientIp + ": " + e);
            e.printStackTrace();
        } finally {
            closeQuietly(clientSocket);
        }
    }

    /** HTTP thường: forward request, xử lý response theo mode, ghi log với quyết định cuối cùng. */
    private void handlePlainHttp(HttpRequest request, Decision decision, OutputStream rawClientOut) {
        TrackingOutputStream clientOut = new TrackingOutputStream(rawClientOut);
        ContentInspector.Mode mode = ContentInspector.modeFor(decision, engine);
        HttpResponse response = null;
        Decision finalDecision = decision;
        int status;

        try (Socket target = new Socket()) {
            target.connect(new InetSocketAddress(request.getHost(), request.getPort()), CONNECT_TIMEOUT_MS);
            target.setSoTimeout(SOCKET_TIMEOUT_MS);

            OutputStream targetOut = target.getOutputStream();
            InputStream targetIn = new BufferedInputStream(target.getInputStream(), 8192);

            targetOut.write(request.toForwardHead(mode != ContentInspector.Mode.PASSTHROUGH));
            if (request.getBody() != null) targetOut.write(request.getBody());
            targetOut.flush();

            // Response tạm 1xx forward ngay; response chính để dành cho bước sau
            HttpResponse head;
            while (true) {
                head = HttpResponseParser.parseHead(targetIn);
                if (head == null) throw new IOException("Server đích đóng kết nối mà không trả response");
                if (!head.isInterim()) break;
                clientOut.write(head.toHeadBytes());
                clientOut.flush();
            }
            response = head;
            clientOut.resetWritten();   // 1xx không tính là đã trả response chính

            if (mode == ContentInspector.Mode.PASSTHROUGH) {
                clientOut.write(response.toHeadBytes());
                clientOut.flush();
                HttpResponseParser.forwardBody(response, request.getMethod(), targetIn, clientOut);
                status = response.getStatusCode();
            } else {
                ContentInspector.Result r = ContentInspector.handle(
                        engine, mode, request, decision, response, targetIn, clientOut);
                finalDecision = r.decision();
                status = r.status();
            }
            System.out.println("[RESPONSE] " + request.getHost() + " <- " + response
                    + " [" + mode + " -> " + finalDecision.action() + "]");

        } catch (SocketTimeoutException e) {
            System.err.println("[TIMEOUT] " + dest(request) + " -> " + e.getMessage());
            status = finishWithError(clientOut, response, 504);
        } catch (IOException e) {
            System.err.println("[ERROR] Lỗi với server đích " + dest(request) + " -> " + e.getMessage());
            status = finishWithError(clientOut, response, 502);
        }
        log(request, finalDecision, status);
    }

    /** HTTPS: tunnel TCP 2 chiều, không giải mã nội dung. */
    private void handleConnectTunnel(HttpRequest request, Decision decision,
                                     InputStream clientIn, OutputStream clientOut) {
        try (Socket target = new Socket()) {
            try {
                target.connect(new InetSocketAddress(request.getHost(), request.getPort()), CONNECT_TIMEOUT_MS);
            } catch (SocketTimeoutException e) {
                System.err.println("[TIMEOUT] CONNECT " + dest(request) + " -> " + e.getMessage());
                sendError(clientOut, 504);
                log(request, decision, 504);
                return;
            } catch (IOException e) {
                System.err.println("[ERROR] CONNECT " + dest(request) + " -> " + e.getMessage());
                sendError(clientOut, 502);
                log(request, decision, 502);
                return;
            }

            clientOut.write(ESTABLISHED);
            clientOut.flush();
            log(request, decision, 200);

            clientSocket.setSoTimeout(TUNNEL_IDLE_TIMEOUT_MS);
            target.setSoTimeout(TUNNEL_IDLE_TIMEOUT_MS);
            InputStream targetIn = target.getInputStream();
            OutputStream targetOut = target.getOutputStream();

            Thread upstream = new Thread(() -> {
                pipe(clientIn, targetOut);
                shutdownOutputQuietly(target);
            }, "tunnel-" + request.getHost());
            upstream.setDaemon(true);
            upstream.start();

            pipe(targetIn, clientOut);
            shutdownOutputQuietly(clientSocket);
            upstream.join(TUNNEL_CLOSE_GRACE_MS);

        } catch (IOException e) {
            System.err.println("[ERROR] Tunnel " + dest(request) + " -> " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(HttpRequest r, Decision d, int status) {
        LogEntry e = new LogEntry();
        e.setTimestamp(LocalDateTime.now().format(TS));
        e.setClientIp(clientIp);
        e.setMethod(r.getMethod());
        e.setHost(r.getHost());
        e.setPath(r.isConnectMethod() ? r.getHost() + ":" + r.getPort() : r.getPath());
        e.setDecision(d.action());
        e.setRuleId(d.ruleId());
        e.setStatusCode(status);
        logClient.submit(e);
    }

    /** Chưa gửi gì cho client thì gửi trang lỗi; đã gửi rồi thì giữ status gốc. */
    private static int finishWithError(TrackingOutputStream out, HttpResponse response, int errorStatus) {
        if (out.isWritten()) return response != null ? response.getStatusCode() : errorStatus;
        sendError(out, errorStatus);
        return errorStatus;
    }

    private static void sendError(OutputStream out, int status) {
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 413 -> "Payload Too Large";
            case 431 -> "Request Header Fields Too Large";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 504 -> "Gateway Timeout";
            default -> "Error";
        };
        byte[] body = ("<html><body><h1>" + status + " " + reason + "</h1></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) { }
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) { }
    }

    private static String dest(HttpRequest r) { return r.getHost() + ":" + r.getPort(); }

    private static void shutdownOutputQuietly(Socket s) {
        try { if (!s.isClosed() && !s.isOutputShutdown()) s.shutdownOutput(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(Socket s) {
        try { if (!s.isClosed()) s.close(); } catch (IOException ignored) { }
    }

    /** Ghi nhận đã gửi byte nào cho client chưa, để biết còn trả được trang 502/504 hay không. */
    private static final class TrackingOutputStream extends FilterOutputStream {
        private boolean written;

        TrackingOutputStream(OutputStream out) { super(out); }

        @Override public void write(int b) throws IOException { written = true; out.write(b); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            written = true;
            out.write(b, off, len);
        }

        void resetWritten() { written = false; }

        boolean isWritten() { return written; }
    }
}