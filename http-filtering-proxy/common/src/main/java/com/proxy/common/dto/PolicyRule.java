package com.proxy.common.dto;

public class PolicyRule {
    private Long id;
    private String type;      // DOMAIN | KEYWORD | IP
    private String value;     // vd: facebook.com, *.gambling.com, "casino", 10.0.0.5
    private String action;    // BLOCK | ALLOW
    private int priority = 100;   // số nhỏ = xét trước
    private boolean enabled = true;
    private String note;
    private String createdAt;

    public PolicyRule() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}