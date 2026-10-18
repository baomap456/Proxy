package com.proxy.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Parse HTTP response từ server đích và forward body về client.
 * Cách xác định hết body: không có body (HEAD, 1xx, 204, 304) -> chunked -> Content-Length
 * -> đọc đến khi server đóng kết nối.
 */
public class HttpResponseParser {

    private static final int MAX_HEAD_SIZE = 64 * 1024;
    private static final int MAX_LINE = 8192;
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] CRLF = {'\r', '\n'};

    /** Đọc status line + headers. Trả về null nếu server đóng kết nối mà chưa gửi byte nào. */
    public static HttpResponse parseHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;   // số ký tự đã khớp của "\r\n\r\n"
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > MAX_HEAD_SIZE) throw new IOException("Header response quá lớn");
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        if (buf.size() == 0) return null;
        if (state != 4) throw new EOFException("Header response bị cắt giữa chừng");

        String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");

        // Status line: HTTP/1.1 200 OK
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) {
            throw new IOException("Status line không hợp lệ: " + lines[0]);
        }
        int code;
        try {
            code = Integer.parseInt(status[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("Status code không hợp lệ: " + lines[0]);
        }
        if (code < 100 || code > 599) throw new IOException("Status code ngoài phạm vi: " + code);

        HttpResponse response = new HttpResponse();
        response.setHttpVersion(status[0]);
        response.setStatusCode(code);
        response.setReasonPhrase(status.length > 2 ? status[2] : "");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            response.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return response;
    }

    /**
     * Forward body từ server đích về client, dừng đúng chỗ body kết thúc.
     * Trả về số byte body (không tính khung chunk) và lưu vào response.
     */
    public static long forwardBody(HttpResponse response, String requestMethod,
                                   InputStream in, OutputStream out) throws IOException {
        long n;
        if (!hasBody(response, requestMethod)) {
            n = 0;
        } else if (isChunked(response)) {
            n = forwardChunked(in, out);
        } else {
            long length = contentLength(response);
            n = length >= 0 ? copyExact(in, out, length) : copyUntilClose(in, out);
        }
        out.flush();
        response.setBodyBytes(n);
        return n;
    }

    private static boolean hasBody(HttpResponse r, String requestMethod) {
        if ("HEAD".equalsIgnoreCase(requestMethod)) return false;
        int s = r.getStatusCode();
        return !(s / 100 == 1 || s == 204 || s == 304);
    }

    private static boolean isChunked(HttpResponse r) {
        for (HttpResponse.Header h : r.getHeaders()) {
            if (h.name().equalsIgnoreCase("Transfer-Encoding")
                    && h.value().toLowerCase(Locale.ROOT).contains("chunked")) {
                return true;
            }
        }
        return false;
    }

    /** Trả về -1 nếu không có header Content-Length. */
    private static long contentLength(HttpResponse r) throws IOException {
        String v = r.getHeader("Content-Length");
        if (v == null) return -1;
        try {
            long len = Long.parseLong(v.trim());
            if (len < 0) throw new NumberFormatException();
            return len;
        } catch (NumberFormatException e) {
            throw new IOException("Content-Length không hợp lệ: " + v);
        }
    }

    private static long copyExact(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new EOFException("Server đóng kết nối khi còn thiếu " + remaining + " byte body");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return length;
    }

    private static long copyUntilClose(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        } catch (SocketTimeoutException e) {
            // Không có độ dài và server giữ kết nối: không có cách nào biết hết body, coi như đã xong
        }
        return total;
    }

    /** Forward nguyên khung chunked (size line, data, trailers) và dừng sau chunk 0. */
    private static long forwardChunked(InputStream in, OutputStream out) throws IOException {
        long total = 0;
        while (true) {
            String sizeLine = readLine(in);
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));

            long size;
            try {
                size = Long.parseLong(sizeLine.split(";", 2)[0].trim(), 16);
                if (size < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IOException("Chunk size không hợp lệ: " + sizeLine);
            }

            if (size == 0) {
                String line;
                while (!(line = readLine(in)).isEmpty()) {   // trailers (có thể không có)
                    out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
                out.write(CRLF);
                return total;
            }

            total += copyExact(in, out, size);
            if (!readLine(in).isEmpty()) throw new IOException("Thiếu CRLF sau dữ liệu chunk");
            out.write(CRLF);
            out.flush();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_LINE) throw new IOException("Dòng chunk quá dài");
        }
        throw new EOFException("Server đóng kết nối giữa chừng khi đọc chunked");
    }
}