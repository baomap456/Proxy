package com.proxy.backend.policy;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.proxy.common.dto.PolicyRule;

@Repository
public class PolicyRepository {

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<PolicyRule> MAPPER = (rs, i) -> {
        PolicyRule r = new PolicyRule();
        r.setId(rs.getLong("id"));
        r.setType(rs.getString("type"));
        r.setValue(rs.getString("value"));
        r.setAction(rs.getString("action"));
        r.setPriority(rs.getInt("priority"));
        r.setEnabled(rs.getInt("enabled") == 1);
        r.setNote(rs.getString("note"));
        r.setCreatedAt(rs.getString("created_at"));
        return r;
    };

    public List<PolicyRule> findAll() {
        return jdbc.query("SELECT * FROM policy_rules ORDER BY priority, id", MAPPER);
    }

    public List<PolicyRule> findActive() {
        return jdbc.query("SELECT * FROM policy_rules WHERE enabled = 1 ORDER BY priority, id", MAPPER);
    }

    public Optional<PolicyRule> findById(long id) {
        return jdbc.query("SELECT * FROM policy_rules WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long insert(PolicyRule r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO policy_rules(type, value, action, priority, enabled, note) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.getType());
            ps.setString(2, r.getValue());
            ps.setString(3, r.getAction());
            ps.setInt(4, r.getPriority());
            ps.setInt(5, r.isEnabled() ? 1 : 0);
            ps.setString(6, r.getNote());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public int update(long id, PolicyRule r) {
        return jdbc.update(
            "UPDATE policy_rules SET type=?, value=?, action=?, priority=?, enabled=?, note=? WHERE id=?",
            r.getType(), r.getValue(), r.getAction(), r.getPriority(),
            r.isEnabled() ? 1 : 0, r.getNote(), id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM policy_rules WHERE id = ?", id);
    }
}