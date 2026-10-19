package com.proxy.policy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxy.dto.PolicyRule;
/**
 * Định kỳ gọi GET {backendUrl}/api/policies/active và nạp vào PolicyEngine.
 * Nếu backend lỗi (không kết nối được, timeout, HTTP != 200, JSON hỏng)
 * thì giữ nguyên rule hiện tại, không đụng vào engine.
 */
public class PolicyRefresher implements AutoCloseable {

    private final PolicyEngine engine;
    private final URI endpoint;
    private final long intervalSeconds;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ScheduledExecutorService scheduler;

    private volatile boolean lastOk = true;   // để chỉ log khi trạng thái đổi, tránh spam

    public PolicyRefresher(PolicyEngine engine, String backendUrl, long intervalSeconds) {
        this.engine = engine;
        this.endpoint = URI.create(backendUrl.replaceAll("/+$", "") + "/api/policies/active");
        this.intervalSeconds = intervalSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "policy-refresher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Nạp ngay một lần, sau đó lặp lại theo chu kỳ. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Không bao giờ ném exception ra ngoài, nếu không ScheduledExecutor sẽ ngừng lập lịch. */
    private void refreshSafely() {
        try {
            refreshOnce();
            if (!lastOk) {
                System.out.println("[PolicyRefresher] Backend đã hoạt động lại, rule được cập nhật.");
                lastOk = true;
            }
        } catch (Exception e) {
            if (lastOk) {
                System.err.println("[PolicyRefresher] Không nạp được rule, giữ " + engine.ruleCount()
                        + " rule cũ. Lý do: " + e);
                lastOk = false;
            }
        }
    }

    private void refreshOnce() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        List<PolicyRule> rules = mapper.readValue(res.body(), new TypeReference<List<PolicyRule>>() { });
        if (rules == null) {
            throw new IllegalStateException("Body là null");
        }
        engine.updateRules(rules);   // chỉ chạy khi mọi thứ ở trên thành công
    }

    @Override
    public void close() { scheduler.shutdownNow(); }
}
