package com.proxy.common.dto;

public class LogEntry {
    private Long id;
    private String timestamp;   // ISO-8601, vd 2026-10-09T14:30:00
    private String clientIp;
    private String method;
    private String host;
    private String path;
    private String decision;    // BLOCK | ALLOW
    private Long ruleId;        // null nếu không do rule
    private Integer statusCode;

    public LogEntry() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
}