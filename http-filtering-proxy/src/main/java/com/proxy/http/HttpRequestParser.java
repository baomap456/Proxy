package com.proxy.http;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
/**
 * Đọc luồng byte từ client và parse thành HttpRequest.
 * Xử lý: request line, headers, body (nếu Content-Length có mặt).
 * Hỗ trợ cả GET/POST thông thường và CONNECT (dùng cho HTTPS tunnel).
 */
public class HttpRequestParser {
public static HttpRequest parse(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int prev = -1, curr;
        // Đọc cho tới khi gặp CRLFCRLF (hết phần header)
        while ((curr = in.read()) != -1) {
            headerBuffer.write(curr);
            if (prev == '\r' && curr == '\n') {
                byte[] soFar = headerBuffer.toByteArray();
                if (endsWithDoubleCrlf(soFar)) break;
            }
            prev = curr;
        }

        if (headerBuffer.size() == 0) {
            return null; // client đóng kết nối, không có gì để đọc
        }

        String headerText = headerBuffer.toString("ISO-8859-1");
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) return null;

        HttpRequest request = new HttpRequest();
        request.setRawRequestLineAndHeaders(headerBuffer.toByteArray());

        // Dòng đầu: METHOD URL HTTP/VERSION
        String[] requestLineParts = lines[0].split(" ");
        if (requestLineParts.length < 3) return null;
        request.setMethod(requestLineParts[0]);
        request.setUrl(requestLineParts[1]);
        request.setHttpVersion(requestLineParts[2]);

        // Các dòng header
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();
            request.addHeader(key, value);
        }

        resolveHostAndPort(request);

        // Đọc body nếu có Content-Length (áp dụng cho POST, PUT...)
        String contentLengthHeader = request.getHeader("Content-Length");
        if (contentLengthHeader != null) {
            int contentLength = Integer.parseInt(contentLengthHeader.trim());
            if (contentLength > 0) {
                byte[] body = readExactBytes(in, contentLength);
                request.setBody(body);
            }
        }

        return request;
    }

    /** Xác định host + port từ header Host hoặc từ URL (trường hợp CONNECT). */
    private static void resolveHostAndPort(HttpRequest request) {
        String host = request.getHeader("Host");
        int port = 80;

        if (request.isConnectMethod()) {
            // CONNECT dùng dạng "host:port" trực tiếp trong URL
            String[] hostPort = request.getUrl().split(":");
            host = hostPort[0];
            port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;
        } else if (host != null) {
            if (host.contains(":")) {
                String[] hostPort = host.split(":");
                host = hostPort[0];
                port = Integer.parseInt(hostPort[1]);
            }
        } else {
            // Fallback: cố lấy host từ URL tuyệt đối nếu không có header Host
            try {
                URI uri = URI.create(request.getUrl());
                if (uri.getHost() != null) {
                    host = uri.getHost();
                    port = uri.getPort() != -1 ? uri.getPort() : 80;
                }
            } catch (Exception ignored) { }
        }

        request.setHost(host);
        request.setPort(port);
    }

    private static boolean endsWithDoubleCrlf(byte[] data) {
        int len = data.length;
        if (len < 4) return false;
        return data[len-4]=='\r' && data[len-3]=='\n' && data[len-2]=='\r' && data[len-1]=='\n';
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, totalRead, length - totalRead);
            if (read == -1) break;
            totalRead += read;
        }
        return buffer;
    }
}
