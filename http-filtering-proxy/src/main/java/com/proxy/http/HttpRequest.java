package com.proxy.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Model đại diện cho 1 HTTP request đã được parse từ raw bytes. */
public class HttpRequest {

    public record Header(String name, String value) { }

    private String method;        // GET, POST, CONNECT...
    private String url;           // absolute-form (http://host/path), origin-form (/path) hoặc host:port (CONNECT)
    private String httpVersion;   // HTTP/1.1
    private String host;
    private int port = 80;
    private final List<Header> headers = new ArrayList<>();
    private byte[] body;
    private static final Set<String> HOP_BY_HOP = Set.of("host", "connection", "proxy-connection", "keep-alive", "proxy-authorization");
    private static final Set<String> INSPECTION_DROP = Set.of(
            "accept-encoding",                      // để server trả nội dung không nén
            "range", "if-range",                    // để nhận cả trang, không nhận từng đoạn
            "if-none-match", "if-modified-since");  // tránh 304: trình duyệt sẽ dùng bản cache chưa lọc
    public byte[] toForwardHead() { return toForwardHead(false); }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public List<Header> getHeaders() { return Collections.unmodifiableList(headers); }
    public void addHeader(String name, String value) { headers.add(new Header(name, value)); }

    /** Giá trị header đầu tiên, không phân biệt hoa thường. */
    public String getHeader(String name) {
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) return h.value();
        }
        return null;
    }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    public boolean isConnectMethod() { return "CONNECT".equalsIgnoreCase(method); }

    /** Trình duyệt gửi qua proxy dùng dạng tuyệt đối: GET http://host/path HTTP/1.1 */
    public boolean isAbsoluteForm() {
        return url != null && url.regionMatches(true, 0, "http://", 0, 7);
    }

    /** Path + query (origin-form), dùng để forward tới server đích và ghi log. */
    public String getPath() {
        if (isConnectMethod()) return "";
        if (!isAbsoluteForm()) return url;
        int end = authorityEnd(url);
        if (end == url.length()) return "/";
        String rest = url.substring(end);
        return rest.charAt(0) == '/' ? rest : "/" + rest;
    }

    /** Phần host[:port] của URL tuyệt đối. */
    static String authorityOf(String absoluteUrl) {
        return absoluteUrl.substring(7, authorityEnd(absoluteUrl));
    }

    private static int authorityEnd(String absoluteUrl) {
        int i = 7;
        while (i < absoluteUrl.length() && "/?#".indexOf(absoluteUrl.charAt(i)) < 0) i++;
        return i;
    }

    /**
     * Dựng request line + headers để gửi tới server đích:
     * - đổi URL về origin-form (/path?query)
     * - Host lấy theo host/port đã xác định (theo RFC 7230, ưu tiên authority của URL)
     * - bỏ header hop-by-hop, gửi "Connection: close" để server đích đóng kết nối khi xong
     */
    public byte[] toForwardHead(boolean forInspection) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(getPath()).append(' ').append(httpVersion).append("\r\n");
        sb.append("Host: ").append(hostHeaderValue()).append("\r\n");
        for (Header h : headers) {
            String n = h.name().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(n)) continue;
            if (forInspection && INSPECTION_DROP.contains(n)) continue;
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String hostHeaderValue() {
        String h = host.contains(":") ? "[" + host + "]" : host;   // IPv6 cần ngoặc vuông
        return port == 80 ? h : h + ":" + port;
    }

    @Override
    public String toString() {
        return method + " " + url + " " + httpVersion + " (host=" + host + ", port=" + port + ")";
    }
}