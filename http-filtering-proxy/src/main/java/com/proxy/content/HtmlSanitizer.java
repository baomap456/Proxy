package com.proxy.content;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Làm sạch HTML cho chế độ ISOLATE theo kiểu allowlist (jsoup Safelist):
 * chỉ giữ thẻ/thuộc tính hiển thị (văn bản, bảng, ảnh, link http/https).
 * Bị loại: script, iframe, object/embed, form/input/button, thuộc tính on*, link javascript:...
 * Stylesheet được giữ lại để trang không vỡ giao diện. Chèn banner báo đang cách ly.
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("article", "aside", "footer", "header", "main", "nav", "section",
                     "figure", "figcaption", "hr", "mark", "time", "abbr")
            .addAttributes(":all", "class", "id", "style", "title", "lang", "dir");

    private static final String BANNER =
            "<div style=\"background:#fef3c7;color:#92400e;border-bottom:2px solid #f59e0b;"
          + "padding:10px 16px;font:14px/1.4 system-ui,sans-serif\">"
          + "🛡️ Trang này đang được xem ở <b>chế độ cách ly</b>: "
          + "script, form và nội dung nhúng đã bị loại bỏ.</div>";

    private HtmlSanitizer() { }

    public static byte[] sanitize(byte[] html, String charset, String baseUri) throws IOException {
        Document dirty = parse(html, charset, baseUri);
        Document clean = new Cleaner(SAFELIST).clean(dirty);   // chỉ lấy phần body đã lọc

        clean.title(dirty.title());
        clean.head().prependElement("meta").attr("charset", "UTF-8");
        for (Element link : dirty.select("link[rel~=(?i)stylesheet][href]")) {
            String href = link.absUrl("href");
            if (href.startsWith("http://") || href.startsWith("https://")) {
                clean.head().appendElement("link").attr("rel", "stylesheet").attr("href", href);
            }
        }
        for (Element style : dirty.select("style")) {
            clean.head().appendElement("style").appendChild(new DataNode(style.data()));
        }
        clean.body().prepend(BANNER);
        clean.outputSettings().charset(StandardCharsets.UTF_8);
        return clean.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    /** Parse HTML; charset null hoặc không hợp lệ thì để jsoup tự dò từ thẻ meta/BOM. */
    static Document parse(byte[] html, String charset, String baseUri) throws IOException {
        try {
            return Jsoup.parse(new ByteArrayInputStream(html), charset, baseUri);
        } catch (IllegalArgumentException e) {
            return Jsoup.parse(new ByteArrayInputStream(html), null, baseUri);
        }
    }
}