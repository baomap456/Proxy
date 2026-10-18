package com.proxy;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import com.proxy.dto.PolicyRule;
import com.proxy.policy.Decision;
import com.proxy.policy.PolicyEngine;

public class PolicyEngineTest {

    private PolicyEngine engine;

    private static PolicyRule rule(long id, String type, String value, String action,
                                   int priority, boolean enabled) {
        PolicyRule r = new PolicyRule();
        r.setId(id);
        r.setType(type);
        r.setValue(value);
        r.setAction(action);
        r.setPriority(priority);
        r.setEnabled(enabled);
        return r;
    }

    private static PolicyRule rule(long id, String type, String value, String action, int priority) {
        return rule(id, type, value, action, priority, true);
    }

    @Before
    public void setUp() { engine = new PolicyEngine(); }

    @Test
    public void noRules_allowsWithoutRuleId() {
        Decision d = engine.evaluate("1.1.1.1", "example.com", "/");
        assertFalse(d.isBlocked());
        assertNull(d.ruleId());
    }

    @Test
    public void lowerPriorityNumberWins() {
        engine.updateRules(List.of(
            rule(1, "DOMAIN", "example.com", "BLOCK", 10),
            rule(2, "DOMAIN", "good.example.com", "ALLOW", 1)));

        Decision allowed = engine.evaluate("1.1.1.1", "good.example.com", "/");
        assertEquals("ALLOW", allowed.action());
        assertEquals(Long.valueOf(2), allowed.ruleId());

        Decision blocked = engine.evaluate("1.1.1.1", "other.example.com", "/");
        assertTrue(blocked.isBlocked());
        assertEquals(Long.valueOf(1), blocked.ruleId());
    }

    @Test
    public void samePriority_lowerIdWins() {
        engine.updateRules(List.of(
            rule(7, "DOMAIN", "example.com", "ALLOW", 5),
            rule(3, "DOMAIN", "example.com", "BLOCK", 5)));
        assertEquals(Long.valueOf(3), engine.evaluate("1.1.1.1", "example.com", "/").ruleId());
    }

    @Test
    public void domain_matchesExactAndSubdomain_butNotLookalike() {
        engine.updateRules(List.of(rule(1, "DOMAIN", "facebook.com", "BLOCK", 100)));
        assertTrue(engine.evaluate("1.1.1.1", "facebook.com", "/").isBlocked());
        assertTrue(engine.evaluate("1.1.1.1", "www.facebook.com", "/").isBlocked());
        assertFalse(engine.evaluate("1.1.1.1", "notfacebook.com", "/").isBlocked());
        assertFalse(engine.evaluate("1.1.1.1", "facebook.com.evil.org", "/").isBlocked());
    }

    @Test
    public void domain_ignoresCasePortAndTrailingDot() {
        engine.updateRules(List.of(rule(1, "DOMAIN", "Facebook.COM", "BLOCK", 100)));
        assertTrue(engine.evaluate("1.1.1.1", "WWW.FACEBOOK.COM:443", "").isBlocked());
        assertTrue(engine.evaluate("1.1.1.1", "facebook.com.", "").isBlocked());
    }

    @Test
    public void domain_wildcardMatchesSubdomainOnly() {
        engine.updateRules(List.of(rule(1, "DOMAIN", "*.gambling.com", "BLOCK", 100)));
        assertTrue(engine.evaluate("1.1.1.1", "www.gambling.com", "/").isBlocked());
        assertFalse(engine.evaluate("1.1.1.1", "gambling.com", "/").isBlocked());
    }

    @Test
    public void keyword_matchesHostOrUrl_ignoringCase() {
        engine.updateRules(List.of(rule(1, "KEYWORD", "Casino", "BLOCK", 100)));
        assertTrue(engine.evaluate("1.1.1.1", "www.CASINO-x.com", "").isBlocked());
        assertTrue(engine.evaluate("1.1.1.1", "example.com", "/Play/Casino/1").isBlocked());
        assertFalse(engine.evaluate("1.1.1.1", "example.com", "/home").isBlocked());
    }

    @Test
    public void ip_exactMatchOnClientOrDestinationHost() {
        engine.updateRules(List.of(rule(1, "IP", "10.0.0.5", "BLOCK", 100)));
        assertTrue(engine.evaluate("10.0.0.5", "example.com", "/").isBlocked());
        assertFalse(engine.evaluate("10.0.0.6", "example.com", "/").isBlocked());
        assertTrue(engine.evaluate("10.0.0.6", "10.0.0.5", "/").isBlocked());
    }

    @Test
    public void ip_cidr() {
        engine.updateRules(List.of(rule(1, "IP", "192.168.1.0/24", "BLOCK", 100)));
        assertTrue(engine.evaluate("192.168.1.77", "example.com", "/").isBlocked());
        assertFalse(engine.evaluate("192.168.2.1", "example.com", "/").isBlocked());
    }

    @Test
    public void disabledRule_isIgnored() {
        engine.updateRules(List.of(rule(1, "DOMAIN", "example.com", "BLOCK", 1, false)));
        assertFalse(engine.evaluate("1.1.1.1", "example.com", "/").isBlocked());
        assertEquals(0, engine.ruleCount());
    }

    @Test
    public void invalidRules_areSkippedWithoutException() {
        engine.updateRules(Arrays.asList(
            null,
            rule(1, "FOO", "x.com", "BLOCK", 1),
            rule(2, "DOMAIN", "x.com", "DENY", 1),
            rule(3, "DOMAIN", "  ", "BLOCK", 1),
            rule(4, "IP", "10.0.0.0/99", "BLOCK", 1),
            rule(5, "IP", "abc/xyz", "BLOCK", 1),
            rule(6, "DOMAIN", "ok.com", "BLOCK", 1)));
        assertEquals(1, engine.ruleCount());
        assertTrue(engine.evaluate("1.1.1.1", "ok.com", "/").isBlocked());
    }

    @Test
    public void nullInputs_doNotThrow() {
        engine.updateRules(null);
        assertFalse(engine.evaluate(null, null, null).isBlocked());
        engine.updateRules(List.of(rule(1, "KEYWORD", "x", "BLOCK", 1)));
        assertFalse(engine.evaluate(null, null, null).isBlocked());
    }
}
