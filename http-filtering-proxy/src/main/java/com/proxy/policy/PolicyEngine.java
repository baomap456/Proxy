package com.proxy.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.proxy.dto.PolicyRule;

/**
 * Đánh giá request theo danh sách rule DOMAIN/KEYWORD/IP.
 * Rule enabled, sắp theo priority tăng dần (cùng priority thì id nhỏ trước),
 * rule khớp đầu tiên thắng. Không rule nào khớp thì ALLOW.
 * An toàn đa luồng: updateRules thay cả danh sách một lần (copy-on-write).
 */
public class PolicyEngine {

    private static final Set<String> TYPES = Set.of("DOMAIN", "KEYWORD", "IP");
    private static final Set<String> ACTIONS = Set.of("BLOCK", "ALLOW");

    private record Target(String clientIp, String host, String url) { }
    private record CompiledRule(PolicyRule rule, Predicate<Target> matcher) { }

    private volatile List<CompiledRule> compiled = List.of();

    /** Nạp (hoặc nạp lại) toàn bộ rule. Rule không hợp lệ bị bỏ qua, không văng exception. */
    public void updateRules(List<PolicyRule> rules) {
        List<PolicyRule> usable = new ArrayList<>();
        if (rules != null) {
            for (PolicyRule r : rules) {
                if (isUsable(r)) usable.add(r);
            }
        }
        usable.sort(Comparator.comparingInt(PolicyRule::getPriority)
                .thenComparing(PolicyRule::getId, Comparator.nullsLast(Comparator.<Long>naturalOrder())));

        List<CompiledRule> next = new ArrayList<>();
        for (PolicyRule r : usable) {
            Predicate<Target> matcher = switch (r.getType()) {
                case "DOMAIN" -> domainMatcher(r.getValue());
                case "KEYWORD" -> keywordMatcher(r.getValue());
                default -> ipMatcher(r.getValue());
            };
            if (matcher != null) next.add(new CompiledRule(r, matcher));
        }
        this.compiled = List.copyOf(next);
    }

    /**
     * @param clientIp IP của client kết nối vào proxy
     * @param host     host đích (đã tách khỏi port)
     * @param url      URL hoặc path của request (rỗng với CONNECT)
     */
    public Decision evaluate(String clientIp, String host, String url) {
        Target t = new Target(normalizeIp(clientIp), normalizeHost(host),
                url == null ? "" : url.toLowerCase(Locale.ROOT));
        for (CompiledRule c : compiled) {
            if (c.matcher().test(t)) {
                return new Decision(c.rule().getAction(), c.rule());
            }
        }
        return Decision.DEFAULT_ALLOW;
    }

    public int ruleCount() { return compiled.size(); }

    // ---------- Kiểm tra rule ----------

    private static boolean isUsable(PolicyRule r) {
        // Set.of(...).contains(null) ném NPE nên phải kiểm tra null trước
        return r != null && r.isEnabled()
                && r.getType() != null && TYPES.contains(r.getType())
                && r.getAction() != null && ACTIONS.contains(r.getAction())
                && r.getValue() != null && !r.getValue().isBlank();
    }

    // ---------- DOMAIN ----------

    /** "example.com" khớp example.com và mọi subdomain; "*.example.com" chỉ khớp subdomain. */
    private static Predicate<Target> domainMatcher(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        boolean subdomainOnly = v.startsWith("*.");
        String domain = normalizeHost(subdomainOnly ? v.substring(2) : v);
        if (domain.isEmpty()) return null;
        String suffix = "." + domain;
        return t -> {
            String h = t.host();
            if (h.isEmpty()) return false;
            return (!subdomainOnly && h.equals(domain)) || h.endsWith(suffix);
        };
    }

    // ---------- KEYWORD ----------

    /** Khớp không phân biệt hoa thường trên host hoặc URL. */
    private static Predicate<Target> keywordMatcher(String value) {
        String k = value.trim().toLowerCase(Locale.ROOT);
        return t -> t.host().contains(k) || t.url().contains(k);
    }

    // ---------- IP ----------

    /** IPv4 chính xác hoặc CIDR (vd 10.0.0.0/24), khớp với IP client hoặc host đích nếu host là IP. */
    private static Predicate<Target> ipMatcher(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        String addr = v;
        int bits = 32;
        int slash = v.indexOf('/');
        if (slash >= 0) {
            addr = v.substring(0, slash).trim();
            try {
                bits = Integer.parseInt(v.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > 32) return null;
        }

        Long base = parseIpv4(addr);
        if (base == null) {
            if (slash >= 0) return null;                // CIDR chỉ hỗ trợ IPv4
            String exact = normalizeHost(addr);         // IPv6: so khớp chuỗi
            if (exact.isEmpty()) return null;
            return t -> exact.equals(t.clientIp()) || exact.equals(t.host());
        }

        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        long network = base & mask;
        return t -> inNetwork(parseIpv4(t.clientIp()), network, mask)
                 || inNetwork(parseIpv4(t.host()), network, mask);
    }

    private static boolean inNetwork(Long ip, long network, long mask) {
        return ip != null && (ip & mask) == network;
    }

    /** Parse IPv4 thủ công (không dùng InetAddress để tránh tra DNS). Trả null nếu không phải IPv4. */
    private static Long parseIpv4(String s) {
        if (s == null) return null;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        long result = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return null;
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') return null;
            }
            int n = Integer.parseInt(p);
            if (n > 255) return null;
            result = (result << 8) | n;
        }
        return result;
    }

    // ---------- Chuẩn hóa ----------

    /** Chữ thường, bỏ port, bỏ ngoặc IPv6, bỏ dấu chấm cuối. */
    private static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close > 0) h = h.substring(1, close);
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0 && colon == h.lastIndexOf(':')) h = h.substring(0, colon);
        }
        int len = h.length();
        while (len > 0 && h.charAt(len - 1) == '.') len--;
        return h.substring(0, len);
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return "";
        String s = ip.trim().toLowerCase(Locale.ROOT);
        int scope = s.indexOf('%');
        return scope >= 0 ? s.substring(0, scope) : s;
    }
}
