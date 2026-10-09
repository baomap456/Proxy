package com.proxy.backend.log;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.proxy.common.dto.LogEntry;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final Set<String> DECISIONS = Set.of("BLOCK", "ALLOW");

    private final JdbcTemplate jdbc;

    public LogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<LogEntry> MAPPER = (rs, i) -> {
        LogEntry e = new LogEntry();
        e.setId(rs.getLong("id"));
        e.setTimestamp(rs.getString("timestamp"));
        e.setClientIp(rs.getString("client_ip"));
        e.setMethod(rs.getString("method"));
        e.setHost(rs.getString("host"));
        e.setPath(rs.getString("path"));
        e.setDecision(rs.getString("decision"));
        long rule = rs.getLong("rule_id");
        e.setRuleId(rs.wasNull() ? null : rule);
        int status = rs.getInt("status_code");
        e.setStatusCode(rs.wasNull() ? null : status);
        return e;
    };

    // ---------- Issue #11 ----------
    @PostMapping("/batch")
    @Transactional
    public Map<String, Integer> batch(@RequestBody List<LogEntry> logs) {
        if (logs == null || logs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch rỗng");
        }
        for (LogEntry e : logs) {
            if (e.getDecision() == null || !DECISIONS.contains(e.getDecision())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision phải là BLOCK hoặc ALLOW");
            }
            if (e.getTimestamp() == null || e.getTimestamp().isBlank()) {
                e.setTimestamp(LocalDateTime.now().toString());
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO access_logs(timestamp, client_ip, method, host, path, decision, rule_id, status_code) "
          + "VALUES (?,?,?,?,?,?,?,?)",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    LogEntry e = logs.get(i);
                    ps.setString(1, e.getTimestamp());
                    ps.setString(2, e.getClientIp());
                    ps.setString(3, e.getMethod());
                    ps.setString(4, e.getHost());
                    ps.setString(5, e.getPath());
                    ps.setString(6, e.getDecision());
                    if (e.getRuleId() == null) ps.setNull(7, Types.INTEGER);
                    else ps.setLong(7, e.getRuleId());
                    if (e.getStatusCode() == null) ps.setNull(8, Types.INTEGER);
                    else ps.setInt(8, e.getStatusCode());
                }
                @Override
                public int getBatchSize() { return logs.size(); }
            });

        return Map.of("inserted", logs.size());
    }

    // ---------- Issue #16 ----------
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String host,
                                    @RequestParam(required = false) String decision) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 100);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (host != null && !host.isBlank()) {
            where.append(" AND host LIKE ?");
            args.add("%" + host.trim() + "%");
        }
        if (decision != null && !decision.isBlank()) {
            where.append(" AND decision = ?");
            args.add(decision.trim().toUpperCase());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM access_logs" + where, Long.class, args.toArray());
        long totalElements = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<LogEntry> content = jdbc.query(
            "SELECT * FROM access_logs" + where + " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?",
            MAPPER, pageArgs.toArray());

        return Map.of(
            "content", content,
            "page", page,
            "size", size,
            "totalElements", totalElements,
            "totalPages", (int) Math.ceil(totalElements / (double) size));
    }
}