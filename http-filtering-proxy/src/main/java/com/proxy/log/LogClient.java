package com.proxy.log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxy.dto.LogEntry;

/**
 * Gửi access log về Backend theo lô, không làm chậm luồng xử lý request.
 * - submit(): đưa vào BlockingQueue; hàng đợi đầy thì bỏ bản ghi (không bao giờ block ClientHandler).
 * - Luồng nền gom tối đa batchSize bản ghi, hoặc đợi tối đa flushIntervalMs, rồi POST /api/logs/batch.
 * - Lỗi mạng / 5xx: retry với backoff (500ms, 1s); hết lượt thì bỏ lô.
 * - 4xx: bỏ lô ngay vì dữ liệu sai, gửi lại cũng vô ích.
 */
public class LogClient implements AutoCloseable {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private final BlockingQueue<LogEntry> queue;
    private final URI endpoint;
    private final int batchSize;
    private final long flushIntervalMs;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Thread worker;

    private volatile boolean running = true;
    private boolean lastOk = true;            // chỉ worker dùng; để log khi trạng thái đổi, tránh spam
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public LogClient(String backendUrl, int batchSize, long flushIntervalMs, int queueCapacity) {
        this.endpoint = URI.create(backendUrl.replaceAll("/+$", "") + "/api/logs/batch");
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::loop, "log-client");
        this.worker.setDaemon(true);
    }

    public void start() { worker.start(); }

    /** Gọi từ ClientHandler. Không bao giờ block. */
    public void submit(LogEntry entry) {
        if (entry == null) return;
        if (!running || !queue.offer(entry)) {
            long d = dropped.incrementAndGet();
            if (d == 1 || d % 1000 == 0) {
                System.err.println("[LogClient] Hàng đợi đầy hoặc đã dừng, tổng số bản ghi đã bỏ: " + d);
            }
        }
    }

    private void loop() {
        List<LogEntry> batch = new ArrayList<>(batchSize);
        long deadline = System.currentTimeMillis() + flushIntervalMs;

        while (running || !queue.isEmpty() || !batch.isEmpty()) {
            try {
                long wait = Math.max(0, deadline - System.currentTimeMillis());
                LogEntry first = queue.poll(running ? wait : 0, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - batch.size());
                }

                boolean due = batch.size() >= batchSize
                        || System.currentTimeMillis() >= deadline
                        || !running;
                if (due) {
                    if (!batch.isEmpty()) {
                        send(batch);
                        batch = new ArrayList<>(batchSize);
                    }
                    deadline = System.currentTimeMillis() + flushIntervalMs;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {   // không để luồng nền chết vì lỗi bất ngờ
                System.err.println("[LogClient] Lỗi không mong đợi, bỏ lô hiện tại: " + e);
                batch = new ArrayList<>(batchSize);
            }
        }
    }

    private void send(List<LogEntry> batch) throws InterruptedException {
        String json;
        try {
            json = mapper.writeValueAsString(batch);
        } catch (Exception e) {
            drop(batch, "không serialize được JSON: " + e);
            return;
        }

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code / 100 == 2) {
                    sent.addAndGet(batch.size());
                    if (!lastOk) {
                        System.out.println("[LogClient] Backend đã nhận log trở lại.");
                        lastOk = true;
                    }
                    return;
                }
                lastError = "HTTP " + code + " " + res.body();
                if (code / 100 == 4) break;
            } catch (IOException e) {
                lastError = e.toString();
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
            }
        }
        drop(batch, lastError);
    }

    private void drop(List<LogEntry> batch, String reason) {
        long total = dropped.addAndGet(batch.size());
        if (lastOk) {
            System.err.println("[LogClient] Không gửi được log về Backend, bỏ lô " + batch.size()
                    + " bản ghi (tổng đã bỏ: " + total + "). Lý do: " + reason);
            lastOk = false;
        }
    }

    /** Dừng nhận log mới, gửi nốt những gì còn trong hàng đợi (tối đa ~5 giây). */
    @Override
    public void close() {
        running = false;
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[LogClient] Đã dừng. Đã gửi: " + sent.get() + ", đã bỏ: " + dropped.get());
    }
}