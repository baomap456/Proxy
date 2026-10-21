package com.proxy.http;

import java.io.IOException;

/** Request từ client sai định dạng. Mang theo mã HTTP để ClientHandler trả về cho client. */
public class HttpParseException extends IOException {
    private final int status;

    public HttpParseException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}