package com.proxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.proxy.log.LogClient;
import com.proxy.policy.PolicyEngine;
import com.proxy.policy.PolicyRefresher;

public class ProxyServer {
    private static final int PORT = 8888;
    private static final int THREAD_POOL_SIZE = 200;   // mỗi tunnel HTTPS giữ 1 thread trong suốt thời gian mở
    private static final String BACKEND_URL = "http://localhost:8080";
    private static final long REFRESH_SECONDS = 5;
    private static final int LOG_BATCH_SIZE = 50;
    private static final long LOG_FLUSH_MS = 2000;
    private static final int LOG_QUEUE_CAPACITY = 10_000;

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        PolicyEngine engine = new PolicyEngine();   // dùng chung cho mọi ClientHandler
        PolicyRefresher refresher = new PolicyRefresher(engine, BACKEND_URL, REFRESH_SECONDS);
        LogClient logClient = new LogClient(BACKEND_URL, LOG_BATCH_SIZE, LOG_FLUSH_MS, LOG_QUEUE_CAPACITY);

        refresher.start();
        logClient.start();

        // Ctrl+C không chạy khối finally, nên dùng shutdown hook để gửi nốt log còn trong hàng đợi
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Đang tắt proxy...");
            refresher.close();
            logClient.close();
            pool.shutdownNow();
        }, "shutdown"));

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("=== Proxy Server dang chay tai port " + PORT + " ===");
            System.out.println("Cau hinh trinh duyet: Proxy = 127.0.0.1, Port = " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, engine, logClient));
            }
        } catch (IOException e) {
            System.err.println("Loi khoi dong server: " + e.getMessage());
            System.exit(1);   // để shutdown hook chạy
        }
    }
}