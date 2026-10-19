package com.proxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.proxy.policy.PolicyEngine;
import com.proxy.policy.PolicyRefresher;

public class ProxyServer {
    private static final int PORT = 8888;
    private static final int THREAD_POOL_SIZE = 50;
    private static final String BACKEND_URL = "http://localhost:8080";
    private static final long REFRESH_SECONDS = 10;

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        // Một engine duy nhất, dùng chung cho mọi ClientHandler
        PolicyEngine engine = new PolicyEngine();
        PolicyRefresher refresher = new PolicyRefresher(engine, BACKEND_URL, REFRESH_SECONDS);
        refresher.start();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("=== Proxy Server (CORE) dang chay tai port " + PORT + " ===");
            System.out.println("Cau hinh trinh duyet: Proxy = 127.0.0.1, Port = " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, engine));
            }
        } catch (IOException e) {
            System.err.println("Loi khoi dong server: " + e.getMessage());
        } finally {
            refresher.close();
            pool.shutdown();
        }
    }
}