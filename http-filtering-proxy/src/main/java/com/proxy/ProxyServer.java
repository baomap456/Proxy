package com.proxy;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class ProxyServer {
     private static final int PORT = 8080;
    private static final int THREAD_POOL_SIZE = 50;

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("=== Proxy Server (CORE) dang chay tai port " + PORT + " ===");
            System.out.println("Cau hinh trinh duyet: Proxy = 127.0.0.1, Port = " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Loi khoi dong server: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }
}
