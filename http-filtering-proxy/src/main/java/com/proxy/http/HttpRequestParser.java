package com.proxy.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Đọc luồng byte từ client và parse thành HttpRequest.
 * Request sai định dạng -> ném HttpParseException kèm mã lỗi (400/413/431/501).
 * Trả về null khi client đóng kết nối mà chưa gửi byte nào.
 * Lưu ý: đọc từng byte từ stream gốc (không buffer) để không "nuốt" mất dữ liệu TLS gửi ngay sau CONNECT.
 */
public final class HttpRequestParser {

    private static final int MAX_HEAD_SIZE = 64 * 1024;
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;

    private HttpRequestParser() { }

    public static HttpRequest parse(InputStream in) throws IOException {
        byte[] head = readHead(in);
        if (head == null) return null;

        String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n");

        // Dòng đầu: METHOD URL HTTP/VERSION
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3 || !isMethodToken(parts[0]) || parts[1].isEmpty()
                || !parts[2].startsWith("HTTP/1.")) {
            throw bad("Request line không hợp lệ: " + lines[0]);
        }

        HttpRequest req = new HttpRequest();
        req.setMethod(parts[0]);
        req.setUrl(parts[1]);
        req.setHttpVersion(parts[2]);

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                throw bad("Header không hợp lệ: " + line);
            }
            req.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }

        resolveHostAndPort(req);
        readBody(in, req);
        return req;
    }

    /** Đọc tới CRLFCRLF. Bỏ qua CRLF thừa trước request line (RFC 7230 mục 3.5). */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;   // số ký tự đã khớp của "\r\n\r\n"
        int b;
        while ((b = in.read()) != -1) {
            if (buf.size() == 0 && (b == '\r' || b == '\n')) continue;
            buf.write(b);
            if (buf.size() > MAX_HEAD_SIZE) {
                throw new HttpParseException(431, "Header request quá lớn");
            }
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) return buf.toByteArray();
        }
        if (buf.size() == 0) return null;
        throw bad("Client đóng kết nối khi chưa gửi hết header");
    }

    private static void resolveHostAndPort(HttpRequest req) throws HttpParseException {
        String url = req.getUrl();
        if (req.isConnectMethod()) {
            applyAuthority(req, url, 443);                              // CONNECT host:port
        } else if (req.isAbsoluteForm()) {
            applyAuthority(req, HttpRequest.authorityOf(url), 80);      // GET http://host:port/path
        } else if (url.startsWith("/")) {
            String host = req.getHeader("Host");
            if (host == null || host.isBlank()) throw bad("Thiếu header Host");
            applyAuthority(req, host, 80);
        } else {
            throw bad("Dạng URL không được hỗ trợ: " + url);
        }
    }

    /** Tách "host", "host:port", "[ipv6]:port"; bỏ userinfo nếu có. */
    private static void applyAuthority(HttpRequest req, String authority, int defaultPort)
            throws HttpParseException {
        String a = authority == null ? "" : authority.trim();
        int at = a.lastIndexOf('@');
        if (at >= 0) a = a.substring(at + 1);

        String host;
        String portStr = null;
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close < 0) throw bad("Host IPv6 không hợp lệ: " + authority);
            host = a.substring(1, close);
            String rest = a.substring(close + 1);
            if (rest.startsWith(":")) portStr = rest.substring(1);
            else if (!rest.isEmpty()) throw bad("Host không hợp lệ: " + authority);
        } else {
            int colon = a.indexOf(':');
            if (colon >= 0) {
                if (colon != a.lastIndexOf(':')) throw bad("Host không hợp lệ: " + authority);
                host = a.substring(0, colon);
                portStr = a.substring(colon + 1);
            } else {
                host = a;
            }
        }
        if (host.isEmpty()) throw bad("Host rỗng: " + authority);

        int port = defaultPort;
        if (portStr != null && !portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                throw bad("Port không hợp lệ: " + authority);
            }
            if (port < 1 || port > 65535) throw bad("Port ngoài phạm vi: " + authority);
        }
        req.setHost(host);
        req.setPort(port);
    }

    private static void readBody(InputStream in, HttpRequest req) throws IOException {
        if (req.isConnectMethod()) return;
        if (req.getHeader("Transfer-Encoding") != null) {
            throw new HttpParseException(501, "Chưa hỗ trợ request body dạng Transfer-Encoding");
        }
        String cl = req.getHeader("Content-Length");
        if (cl == null) return;

        long length;
        try {
            length = Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            throw bad("Content-Length không hợp lệ: " + cl);
        }
        if (length < 0) throw bad("Content-Length âm: " + cl);
        if (length > MAX_BODY_SIZE) {
            throw new HttpParseException(413, "Body quá lớn: " + length + " byte");
        }
        if (length > 0) req.setBody(readExact(in, (int) length));
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int total = 0;
        while (total < length) {
            int n = in.read(buf, total, length - total);
            if (n == -1) throw bad("Client đóng kết nối khi body chưa gửi đủ " + length + " byte");
            total += n;
        }
        return buf;
    }

    private static boolean isMethodToken(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static HttpParseException bad(String message) {
        return new HttpParseException(400, message);
    }
}