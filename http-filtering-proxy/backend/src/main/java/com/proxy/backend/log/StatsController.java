package com.proxy.backend.log;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) FROM access_logs", Long.class);
        Long blockedObj = jdbc.queryForObject(
            "SELECT COUNT(*) FROM access_logs WHERE decision = 'BLOCK'", Long.class);
        long total = totalObj == null ? 0 : totalObj;
        long blocked = blockedObj == null ? 0 : blockedObj;

        List<Map<String, Object>> top = jdbc.queryForList(
            "SELECT host, COUNT(*) AS count FROM access_logs "
          + "WHERE host IS NOT NULL AND host <> '' "
          + "GROUP BY host ORDER BY count DESC LIMIT 5");

        double rate = total == 0 ? 0.0 : Math.round(blocked * 1000.0 / total) / 10.0;

        return Map.of(
            "totalRequests", total,
            "blocked", blocked,
            "blockRate", rate,
            "topDomains", top);
    }
}