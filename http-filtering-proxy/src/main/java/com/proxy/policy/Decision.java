package com.proxy.policy;

import com.proxy.dto.PolicyRule;

/** Kết quả đánh giá: hành động và rule đã khớp (null nếu không rule nào khớp). */
public record Decision(String action, PolicyRule rule) {

    public static final Decision DEFAULT_ALLOW = new Decision("ALLOW", null);

    public boolean isBlocked() { return "BLOCK".equals(action); }

    /** Dùng cho cột rule_id khi ghi log. */
    public Long ruleId() { return rule == null ? null : rule.getId(); }
}
