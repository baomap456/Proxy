package com.proxy.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class WebServer {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Port không hợp lệ, dùng mặc định " + DEFAULT_PORT);
            }
        }

        Javalin app = Javalin.create(config -> {
            // Phục vụ file tĩnh trong src/main/resources/public/
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        // Vào "/" thì chuyển sang Dashboard
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // ---------- API tạm (trả dữ liệu rỗng để UI không bị lỗi 404) ----------
        // Sẽ thay bằng logic thật ở các issue backend sau.
        app.get("/api/policies", ctx -> ctx.contentType("application/json").result("[]"));
        app.get("/api/logs", ctx -> ctx.contentType("application/json").result("[]"));
        app.get("/api/stats", ctx -> ctx.contentType("application/json")
                .result("{\"total\":0,\"block\":0,\"allow\":0,\"rules\":0}"));

        // Lỗi chưa xử lý -> trả 500 dạng JSON
        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            ctx.status(500).contentType("application/json")
               .result("{\"error\":\"Internal Server Error\"}");
        });

        app.start(port);
        System.out.println("Web UI: http://localhost:" + port + "/index.html");
    }
}
