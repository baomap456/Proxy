package com.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.proxy.http.HttpResponse;
import com.proxy.http.HttpResponseParser;

public class HttpResponseParserTest {

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String forward(String raw, String method) throws IOException {
        ByteArrayInputStream in = stream(raw);
        HttpResponse r = HttpResponseParser.parseHead(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponseParser.forwardBody(r, method, in, out);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    public void parseHead_readsStatusAndKeepsDuplicateHeaders() throws IOException {
        HttpResponse r = HttpResponseParser.parseHead(stream(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n"));
        assertEquals(404, r.getStatusCode());
        assertEquals("Not Found", r.getReasonPhrase());
        assertEquals("0", r.getHeader("content-length"));
        assertEquals(3, r.getHeaders().size());
    }

    @Test
    public void forwardBody_contentLength_stopsAtLength() throws IOException {
        assertEquals("hello",
            forward("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA", "GET"));
    }

    @Test
    public void forwardBody_chunked_keepsFramingAndStopsAfterLastChunk() throws IOException {
        String raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                   + "5\r\nhello\r\n0\r\n\r\nEXTRA";
        assertEquals("5\r\nhello\r\n0\r\n\r\n", forward(raw, "GET"));
    }

    @Test
    public void forwardBody_headRequest_hasNoBody() throws IOException {
        assertEquals("", forward("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello", "HEAD"));
    }

    @Test
    public void forwardBody_204_hasNoBody() throws IOException {
        assertEquals("", forward("HTTP/1.1 204 No Content\r\n\r\nEXTRA", "GET"));
    }

    @Test
    public void toHeadBytes_replacesKeepAliveWithClose() throws IOException {
        HttpResponse r = HttpResponseParser.parseHead(stream(
            "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nKeep-Alive: timeout=5\r\nContent-Length: 0\r\n\r\n"));
        String head = new String(r.toHeadBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(head.contains("Connection: close"));
        assertFalse(head.toLowerCase().contains("keep-alive"));
        assertTrue(head.contains("Content-Length: 0"));
    }
}