package com.proxy.filter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.proxy.dto.PolicyRule;
import com.proxy.http.HttpRequest;
import com.proxy.policy.Decision;

/** Tạo response 403 kèm trang HTML thông báo bị chặn. */
public final class BlockPageGenerator {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    private static final String TEMPLATE = """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8">
        <title>Truy cập bị chặn</title>
        <style>
          body { font-family: system-ui, sans-serif; background: #f4f5f7; color: #1f2937; margin: 0; }
          .box { max-width: 560px; margin: 80px auto; background: #fff; border-radius: 12px;
                 padding: 32px; box-shadow: 0 4px 20px rgba(0,0,0,.08); border-top: 6px solid #dc2626; }
          h1 { margin: 0 0 8px; color: #dc2626; font-size: 24px; }
          table { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 14px; }
          td { padding: 8px 0; border-bottom: 1px solid #e5e7eb; vertical-align: top; }
          td:first-child { color: #6b7280; width: 110px; }
          code { word-break: break-all; }
        </style>
        </head>
        <body>
        <div class="box">
          <h1>🚫 Truy cập bị chặn</h1>
          <p>Trang bạn yêu cầu vi phạm chính sách truy cập của hệ thống.</p>
          <table>
            <tr><td>Host</td><td><code>{{HOST}}</code></td></tr>
            <tr><td>URL</td><td><code>{{URL}}</code></td></tr>
            <tr><td>Lý do</td><td>{{REASON}}</td></tr>
            <tr><td>Rule ID</td><td>{{RULE_ID}}</td></tr>
            <tr><td>Thời gian</td><td>{{TIME}}</td></tr>
          </table>
        </div>
        </body>
        </html>
        """;

    private BlockPageGenerator() { }

    

    /** Trả về toàn bộ response (status line + headers + body) để ghi thẳng ra socket. */
    public static byte[] build(HttpRequest request, Decision decision) {
        return build(request, decision, null);
    }

    /** @param detail lý do bổ sung (chặn theo nội dung, cách ly thất bại...), null nếu không có */
    public static byte[] build(HttpRequest request, Decision decision, String detail) {
        PolicyRule rule = decision.rule();
        String reason = rule == null
                ? "Chính sách truy cập"
                : "Khớp rule " + rule.getType() + ": " + rule.getValue() + " → " + rule.getAction()
                  + (rule.getNote() == null || rule.getNote().isBlank() ? "" : " (" + rule.getNote() + ")");
        if (detail != null && !detail.isBlank()) reason = detail + ". " + reason;

        String url;
        if (request.isConnectMethod()) {
            url = "https://" + request.getHost() + (request.getPort() == 443 ? "" : ":" + request.getPort()) + "/";
        } else if (request.isAbsoluteForm()) {
            url = request.getUrl();
        } else {
            url = "http://" + request.getHost() + request.getPath();
        }

        String html = TEMPLATE
                .replace("{{HOST}}", escape(request.getHost()))
                .replace("{{URL}}", escape(url))
                .replace("{{REASON}}", escape(reason))
                .replace("{{RULE_ID}}", decision.ruleId() == null ? "-" : String.valueOf(decision.ruleId()))
                .replace("{{TIME}}", LocalDateTime.now().format(TIME));

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        byte[] head = ("HTTP/1.1 403 Forbidden\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}