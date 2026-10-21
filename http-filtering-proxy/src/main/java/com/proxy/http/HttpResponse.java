package com.proxy.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Model đại diện cho phần đầu (status line + headers) của 1 HTTP response.
 * Header lưu dạng danh sách để giữ nguyên các header trùng tên (vd: Set-Cookie).
 */
public class HttpResponse {

    public record Header(String name, String value) { }

    /** Header luôn bị bỏ khi proxy gửi body đã sửa (giải nén, làm sạch...). */
    private static final Set<String> REWRITE_ALWAYS_DROP = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive", "proxy-connection", "trailer");

    private String httpVersion;
    private int statusCode;
    private String reasonPhrase = "";
    private final List<Header> headers = new ArrayList<>();
    private long bodyBytes;   // số byte body đã forward/đọc (dùng cho log)

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

    /** Content-Type không kèm tham số, chữ thường. Rỗng nếu không có. */
    public String getMimeType() {
        String ct = getHeader("Content-Type");
        if (ct == null) return "";
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
    }

    /** Tham số charset trong Content-Type, null nếu không có. */
    public String getCharset() {
        String ct = getHeader("Content-Type");
        if (ct == null) return null;
        for (String part : ct.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                String v = p.substring(8).trim().replace("\"", "").replace("'", "");
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * Dựng lại status line + headers để gửi về client (body forward nguyên văn).
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

    /**
     * Head cho body đã được proxy sửa (giải nén, làm sạch...): luôn bỏ header framing/hop-by-hop cũ,
     * bỏ thêm các header trong dropHeaders (tên chữ thường), thêm extraHeaders,
     * gắn Content-Length mới và Connection: close.
     */
    public byte[] toRewrittenHead(int bodyLength, Set<String> dropHeaders, List<Header> extraHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append(httpVersion).append(' ').append(statusCode).append(' ')
          .append(reasonPhrase).append("\r\n");
        for (Header h : headers) {
            String n = h.name().toLowerCase(Locale.ROOT);
            if (REWRITE_ALWAYS_DROP.contains(n) || dropHeaders.contains(n)) continue;
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        for (Header h : extraHeaders) {
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        sb.append("Content-Length: ").append(bodyLength).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        return httpVersion + " " + statusCode + " " + reasonPhrase + " (body=" + bodyBytes + " bytes)";
    }
}