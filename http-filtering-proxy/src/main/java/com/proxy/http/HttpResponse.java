package com.proxy.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Model đại diện cho phần đầu (status line + headers) của 1 HTTP response.
 * Header lưu dạng danh sách để giữ nguyên các header trùng tên (vd: Set-Cookie).
 */
public class HttpResponse {

    public record Header(String name, String value) { }

    private String httpVersion;
    private int statusCode;
    private String reasonPhrase = "";
    private final List<Header> headers = new ArrayList<>();
    private long bodyBytes;   // số byte body đã forward (dùng cho log sau này)

    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public String getReasonPhrase() { return reasonPhrase; }
    public void setReasonPhrase(String reasonPhrase) { this.reasonPhrase = reasonPhrase; }

    public List<Header> getHeaders() { return headers; }
    public void addHeader(String name, String value) { headers.add(new Header(name, value)); }

    /** Lấy giá trị header đầu tiên, không phân biệt hoa thường. */
    public String getHeader(String name) {
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) return h.value();
        }
        return null;
    }

    public long getBodyBytes() { return bodyBytes; }
    public void setBodyBytes(long bodyBytes) { this.bodyBytes = bodyBytes; }

    /** Response tạm 1xx (vd: 100 Continue), phía sau còn response chính. 101 không tính. */
    public boolean isInterim() {
        return statusCode >= 100 && statusCode < 200 && statusCode != 101;
    }

    /**
     * Dựng lại status line + headers để gửi về client.
     * Proxy đóng kết nối sau mỗi request nên bỏ các header keep-alive
     * của server đích và báo "Connection: close" cho client.
     */
    public byte[] toHeadBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(httpVersion).append(' ').append(statusCode).append(' ')
          .append(reasonPhrase).append("\r\n");
        for (Header h : headers) {
            String n = h.name();
            if (!isInterim() && (n.equalsIgnoreCase("Connection")
                    || n.equalsIgnoreCase("Keep-Alive")
                    || n.equalsIgnoreCase("Proxy-Connection"))) {
                continue;
            }
            sb.append(n).append(": ").append(h.value()).append("\r\n");
        }
        if (!isInterim()) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        return httpVersion + " " + statusCode + " " + reasonPhrase + " (body=" + bodyBytes + " bytes)";
    }
}