package com.proxy.http;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Model đại diện cho 1 HTTP request đã được parse từ raw bytes.
 */
public class HttpRequest {
    private String method;        // GET, POST, CONNECT...
    private String url;           // URL đầy đủ hoặc path
    private String httpVersion;   // HTTP/1.1
    private String host;          // lấy từ header Host hoặc từ URL (CONNECT)
    private int port = 80;        // mặc định 80, CONNECT thường dùng 443
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body;          // dữ liệu body nếu có (POST...)
    private byte[] rawRequestLineAndHeaders; // dùng để forward nguyên văn

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

    public Map<String, String> getHeaders() { return headers; }
    public void addHeader(String key, String value) { headers.put(key, value); }
    public String getHeader(String key) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    public byte[] getRawRequestLineAndHeaders() { return rawRequestLineAndHeaders; }
    public void setRawRequestLineAndHeaders(byte[] raw) { this.rawRequestLineAndHeaders = raw; }

    public boolean isConnectMethod() {
        return "CONNECT".equalsIgnoreCase(method);
    }

    @Override
    public String toString() {
        return method + " " + url + " " + httpVersion + " (host=" + host + ", port=" + port + ")";
    }
}
