package com.proxy.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.proxy.filter.BlockPageGenerator;
import com.proxy.http.HttpRequest;
import com.proxy.http.HttpResponse;
import com.proxy.http.HttpResponseParser;
import com.proxy.policy.Decision;
import com.proxy.policy.PolicyEngine;

/**
 * Pha 2: xử lý body phản hồi của request HTTP.
 * INSPECT: đọc trọn trang HTML/text, so với rule KEYWORD -> BLOCK (403), ISOLATE (làm sạch) hoặc trả nguyên bản.
 * ISOLATE: HTML được làm sạch; ảnh (trừ SVG), CSS, font cho qua; loại khác (JS, file tải về...) bị chặn.
 * Không xử lý được (quá lớn, nén kiểu lạ): INSPECT -> fail-open, ISOLATE -> fail-closed.
 */
public final class ContentInspector {

    public static final int MAX_INSPECT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DECODED_BYTES = 20 * 1024 * 1024;

    public enum Mode { PASSTHROUGH, INSPECT, ISOLATE }

    /** Quyết định cuối cùng và status thực tế, để ClientHandler ghi log. */
    public record Result(Decision decision, int status) { }

    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private static final Set<String> HTML_ISOLATION_DROP = Set.of(
            "content-encoding", "content-type", "content-security-policy",
            "content-security-policy-report-only", "x-content-type-options",
            "cache-control", "expires", "etag", "last-modified", "link", "refresh");
    private static final List<HttpResponse.Header> HTML_ISOLATION_HEADERS = List.of(
            new HttpResponse.Header("Content-Type", "text/html; charset=UTF-8"),
            new HttpResponse.Header("Content-Security-Policy",
                    "script-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; "
                  + "base-uri 'none'; form-action 'none'"),
            new HttpResponse.Header("X-Content-Type-Options", "nosniff"),
            new HttpResponse.Header("Cache-Control", "no-store"));

    private static final Set<String> TEXT_ISOLATION_DROP = Set.of(
            "content-encoding", "content-security-policy", "content-security-policy-report-only",
            "x-content-type-options", "cache-control", "expires", "etag", "last-modified", "link", "refresh");
    private static final List<HttpResponse.Header> TEXT_ISOLATION_HEADERS = List.of(
            new HttpResponse.Header("Content-Security-Policy", "sandbox"),
            new HttpResponse.Header("X-Content-Type-Options", "nosniff"),
            new HttpResponse.Header("Cache-Control", "no-store"));

    private ContentInspector() { }

    public static Mode modeFor(Decision decision, PolicyEngine engine) {
        if (decision.isIsolated()) return Mode.ISOLATE;
        if (decision.isDefaultAllow() && engine.hasContentRules()) return Mode.INSPECT;
        return Mode.PASSTHROUGH;
    }

    /** Response head đã đọc, body chưa đọc. Ghi kết quả cuối cùng ra client. */
    public static Result handle(PolicyEngine engine, Mode mode, HttpRequest request, Decision decision,
                                HttpResponse response, InputStream targetIn, OutputStream clientOut)
            throws IOException {
        String method = request.getMethod();
        String mime = response.getMimeType();
        boolean html = HTML_TYPES.contains(mime);
        boolean text = html || "text/plain".equals(mime);

        // 1. Không có body, hoặc không phải văn bản
        if (!HttpResponseParser.hasBody(response, method)) {
            return passthrough(request, decision, response, targetIn, clientOut);
        }
        if (!text) {
            if (mode == Mode.INSPECT || isSafeStatic(mime)) {
                return passthrough(request, decision, response, targetIn, clientOut);
            }
            return block(request, decision, clientOut, "Loại nội dung \""
                    + (mime.isEmpty() ? "không rõ" : mime) + "\" không được phép ở chế độ cách ly");
        }

        // 2. Đọc trọn body (có giới hạn)
        if (HttpResponseParser.declaredLength(response) > MAX_INSPECT_BYTES) {
            if (mode == Mode.ISOLATE) return block(request, decision, clientOut, "Trang quá lớn để cách ly");
            System.err.println("[CONTENT] Bỏ qua kiểm tra vì trang quá lớn: " + absoluteUrl(request));
            return passthrough(request, decision, response, targetIn, clientOut);
        }
        byte[] raw;
        try {
            raw = HttpResponseParser.readBody(response, method, targetIn, MAX_INSPECT_BYTES);
        } catch (HttpResponseParser.BodyTooLargeException e) {
            if (mode == Mode.ISOLATE) return block(request, decision, clientOut, "Trang quá lớn để cách ly");
            throw e;   // đã đọc dở, không trả nguyên bản được -> ClientHandler trả 502
        }

        // 3. Giải nén
        byte[] body;
        try {
            body = decode(raw, response.getHeader("Content-Encoding"));
        } catch (IOException e) {
            if (mode == Mode.ISOLATE) {
                return block(request, decision, clientOut, "Không giải nén được nội dung để cách ly ("
                        + e.getMessage() + ")");
            }
            System.err.println("[CONTENT] Không kiểm tra được " + absoluteUrl(request) + ": "
                    + e.getMessage() + " -> trả nguyên bản");
            writeRewritten(clientOut, response, raw, Set.of(), List.of());
            return new Result(decision, response.getStatusCode());
        }

        String baseUri = absoluteUrl(request);
        String charset = response.getCharset();

        // 4. INSPECT: so từ khóa trên văn bản của trang
        if (mode == Mode.INSPECT) {
            String pageText = html
                    ? HtmlSanitizer.parse(body, charset, baseUri).text()
                    : new String(body, toCharset(charset));
            Decision match = engine.evaluateContent(pageText);
            if (match == null || "ALLOW".equals(match.action())) {
                writeRewritten(clientOut, response, body, Set.of("content-encoding"), List.of());
                return new Result(match != null ? match : decision, response.getStatusCode());
            }
            if (match.isBlocked()) {
                System.out.println("[CONTENT-BLOCK] " + baseUri + " <- rule #" + match.ruleId());
                clientOut.write(BlockPageGenerator.build(request, match, "Nội dung trang chứa từ khóa bị chặn"));
                clientOut.flush();
                return new Result(match, 403);
            }
            System.out.println("[CONTENT-ISOLATE] " + baseUri + " <- rule #" + match.ruleId());
            decision = match;   // ISOLATE theo nội dung -> làm sạch như bên dưới
        }

        // 5. ISOLATE
        if (!html) {   // text/plain: không có gì để chạy, chỉ ép trình duyệt hiển thị dạng text
            writeRewritten(clientOut, response, body, TEXT_ISOLATION_DROP, TEXT_ISOLATION_HEADERS);
            return new Result(decision, response.getStatusCode());
        }
        byte[] cleaned;
        try {
            cleaned = HtmlSanitizer.sanitize(body, charset, baseUri);
        } catch (IOException | RuntimeException e) {
            return block(request, decision, clientOut, "Không làm sạch được trang (" + e.getMessage() + ")");
        }
        System.out.println("[ISOLATE] " + baseUri + " (" + body.length + " -> " + cleaned.length + " byte)");
        writeRewritten(clientOut, response, cleaned, HTML_ISOLATION_DROP, HTML_ISOLATION_HEADERS);
        return new Result(decision, response.getStatusCode());
    }

    // ---------- Ghi response ----------

    private static Result passthrough(HttpRequest request, Decision decision, HttpResponse response,
                                      InputStream targetIn, OutputStream clientOut) throws IOException {
        clientOut.write(response.toHeadBytes());
        clientOut.flush();
        HttpResponseParser.forwardBody(response, request.getMethod(), targetIn, clientOut);
        return new Result(decision, response.getStatusCode());
    }

    private static Result block(HttpRequest request, Decision decision, OutputStream clientOut,
                                String detail) throws IOException {
        System.out.println("[ISOLATE-BLOCK] " + absoluteUrl(request) + " -> " + detail);
        clientOut.write(BlockPageGenerator.build(request, decision, detail));
        clientOut.flush();
        return new Result(decision, 403);
    }

    private static void writeRewritten(OutputStream out, HttpResponse response, byte[] body,
                                       Set<String> drop, List<HttpResponse.Header> extra) throws IOException {
        out.write(response.toRewrittenHead(body.length, drop, extra));
        out.write(body);
        out.flush();
    }

    // ---------- Tiện ích ----------

    /** Vẫn cho tải ở chế độ cách ly: ảnh (trừ SVG vì SVG chứa được script), CSS, font. */
    private static boolean isSafeStatic(String mime) {
        return (mime.startsWith("image/") && !mime.equals("image/svg+xml"))
                || mime.equals("text/css")
                || mime.startsWith("font/")
                || mime.equals("application/font-woff");
    }

    private static byte[] decode(byte[] raw, String contentEncoding) throws IOException {
        String enc = contentEncoding == null ? "" : contentEncoding.trim().toLowerCase(Locale.ROOT);
        switch (enc) {
            case "", "identity":
                return raw;
            case "gzip", "x-gzip":
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    return readCapped(in);
                }
            case "deflate":
                try {
                    return inflate(raw, false);   // chuẩn: có header zlib
                } catch (IOException e) {
                    return inflate(raw, true);    // một số server gửi deflate "trần"
                }
            default:
                throw new IOException("không hỗ trợ Content-Encoding: " + contentEncoding);
        }
    }

    private static byte[] inflate(byte[] raw, boolean nowrap) throws IOException {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(nowrap))) {
            return readCapped(in);
        }
    }

    /** Đọc hết stream, chặn "bom nén" (file nhỏ giải nén ra cực lớn). */
    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_DECODED_BYTES) throw new IOException("nội dung sau giải nén quá lớn");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Charset toCharset(String name) {
        if (name == null) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String absoluteUrl(HttpRequest r) {
        if (r.isAbsoluteForm()) return r.getUrl();
        String host = r.getHost().contains(":") ? "[" + r.getHost() + "]" : r.getHost();
        return "http://" + host + (r.getPort() == 80 ? "" : ":" + r.getPort()) + r.getPath();
    }
}