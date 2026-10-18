package com.proxy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import com.proxy.http.HttpRequest;
import com.proxy.http.HttpRequestParser;
import com.proxy.http.HttpResponse;
import com.proxy.http.HttpResponseParser;
/**
 * Xử lý 1 kết nối client: parse request, forward tới server đích, relay response về.
 * PHIÊN BẢN CORE: chưa gọi RuleEngine/FilterEngine/Logger DB - sẽ tích hợp ở bước sau.
 */
public class ClientHandler implements Runnable {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    private final Socket clientSocket;

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            HttpRequest request = HttpRequestParser.parse(clientIn);
            if (request == null || request.getHost() == null) {
                closeQuietly(clientSocket);
                return;
            }

            System.out.println("[REQUEST] " + request);

            if (request.isConnectMethod()) {
                handleConnectTunnel(request, clientSocket, clientOut);
            } else {
                handlePlainHttp(request, clientOut);
            }

        } catch (SocketTimeoutException e) {
            System.err.println("[TIMEOUT] Client hoặc server không phản hồi kịp: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[ERROR] Loi xu ly ket noi: " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }

    /** Xử lý HTTP thường (GET/POST...): forward request, relay response, rồi đóng kết nối. */
    private void handlePlainHttp(HttpRequest request, OutputStream clientOut) {
        boolean responseStarted = false;   // đã gửi response chính về client chưa
        try (Socket targetSocket = new Socket()) {
            targetSocket.connect(new InetSocketAddress(request.getHost(), request.getPort()), CONNECT_TIMEOUT_MS);
            targetSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

            OutputStream targetOut = targetSocket.getOutputStream();
            // Cần buffer vì parser đọc từng byte khi tìm hết header
            InputStream targetIn = new BufferedInputStream(targetSocket.getInputStream(), 8192);

            // Forward nguyên văn request line + headers, rồi body nếu có
            targetOut.write(request.getRawRequestLineAndHeaders());
            if (request.getBody() != null) {
                targetOut.write(request.getBody());
            }
            targetOut.flush();

            // Đọc response; bỏ qua response tạm 1xx (vd: 100 Continue) nhưng vẫn forward về client
            HttpResponse response;
            while (true) {
                response = HttpResponseParser.parseHead(targetIn);
                if (response == null) {
                    throw new IOException("Server đích đóng kết nối mà không trả response");
                }
                clientOut.write(response.toHeadBytes());
                clientOut.flush();
                if (!response.isInterim()) break;
            }
            responseStarted = true;

            HttpResponseParser.forwardBody(response, request.getMethod(), targetIn, clientOut);
            System.out.println("[RESPONSE] " + request.getHost() + " <- " + response);

        } catch (SocketTimeoutException e) {
            System.err.println("[TIMEOUT] " + request.getHost() + ":" + request.getPort() + " -> " + e.getMessage());
            if (!responseStarted) sendSimpleErrorResponse(clientOut, 504, "Gateway Timeout");
        } catch (IOException e) {
            System.err.println("[ERROR] Loi voi server dich " + request.getHost() + ":" + request.getPort() + " -> " + e.getMessage());
            if (!responseStarted) sendSimpleErrorResponse(clientOut, 502, "Bad Gateway");
        }
    }

    /** Xử lý HTTPS: thiết lập tunnel TCP 2 chiều, không đọc/giải mã nội dung (đúng chuẩn CONNECT). */
    private void handleConnectTunnel(HttpRequest request, Socket clientSocket, OutputStream clientOut) {
        try (Socket targetSocket = new Socket()) {
            targetSocket.connect(new InetSocketAddress(request.getHost(), request.getPort()), CONNECT_TIMEOUT_MS);

            // Báo cho client biết tunnel đã sẵn sàng
            String response = "HTTP/1.1 200 Connection Established\r\n\r\n";
            clientOut.write(response.getBytes());
            clientOut.flush();

            // Forward 2 chiều đồng thời bằng 2 thread
            Thread clientToTarget = new Thread(() ->
                copyStream(getInputStreamQuiet(clientSocket), getOutputStreamQuiet(targetSocket)));
            Thread targetToClient = new Thread(() ->
                copyStream(getInputStreamQuiet(targetSocket), clientOut));

            clientToTarget.start();
            targetToClient.start();
            clientToTarget.join();
            targetToClient.join();

        } catch (IOException | InterruptedException e) {
            System.err.println("[ERROR] Loi tunnel CONNECT toi " + request.getHost() + ":" + request.getPort() + " -> " + e.getMessage());
            sendSimpleErrorResponse(clientOut, 502, "Bad Gateway");
        }
    }

    private void copyStream(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException ignored) {
            // Kết nối đóng bình thường khi 1 bên ngắt - không cần log lỗi ồn ào
        }
    }

    private void sendSimpleErrorResponse(OutputStream out, int statusCode, String statusText) {
        try {
            String body = "<html><body><h1>" + statusCode + " " + statusText + "</h1></body></html>";
            String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + body.length() + "\r\n\r\n" + body;
            out.write(response.getBytes());
            out.flush();
        } catch (IOException ignored) { }
    }

    private InputStream getInputStreamQuiet(Socket s) {
        try { return s.getInputStream(); } catch (IOException e) { return InputStream.nullInputStream(); }
    }

    private OutputStream getOutputStreamQuiet(Socket s) {
        try { return s.getOutputStream(); } catch (IOException e) { return OutputStream.nullOutputStream(); }
    }

    private void closeQuietly(Socket s) {
        try { if (!s.isClosed()) s.close(); } catch (IOException ignored) { }
    }
}
