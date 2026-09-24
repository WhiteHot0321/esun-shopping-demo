package com.esun.shop.repository;

import com.esun.shop.dto.AuditLogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only storage for {@code audit_log}. There is deliberately no update or delete method: an audit trail that
 * the application itself can rewrite is not evidence.
 */
@Repository
public class AuditLogRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(String actor, String actorRole, String action, String targetType, String targetId,
                       String beforeJson, String afterJson) {
        // The timestamp is written by the application, not the DB default: the JDBC URL pins serverTimezone to
        // Asia/Taipei while the MySQL server may run in UTC, and a DB-side CURRENT_TIMESTAMP would then read back
        // hours off (and mis-match the from/to filters). App-side writes round-trip consistently.
        jdbcTemplate.update("INSERT INTO audit_log(actor, actor_role, action, target_type, target_id, before_state, "
                        + "after_state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                actor, actorRole, action, targetType, targetId, beforeJson, afterJson,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<AuditLogEntry> search(Filter filter, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("SELECT id, actor, actor_role, action, target_type, target_id, before_state, "
                + "after_state, created_at FROM audit_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", (rs, rowNum) ->
                new AuditLogEntry(rs.getLong("id"), rs.getString("actor"), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        parse(rs.getString("before_state")), parse(rs.getString("after_state")),
                        rs.getTimestamp("created_at").toLocalDateTime()), args.toArray());
    }

    public long count(Filter filter) {
        List<Object> args = new ArrayList<>();
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log" + where(filter, args), Long.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("audit_log 內容不是合法 JSON", ex);
        }
    }

    private static String where(Filter filter, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        add(clauses, args, "actor = ?", filter.actor());
        add(clauses, args, "action = ?", filter.action());
        add(clauses, args, "target_type = ?", filter.targetType());
        add(clauses, args, "target_id = ?", filter.targetId());
        if (filter.from() != null) {
            clauses.add("created_at >= ?");
            args.add(Timestamp.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            clauses.add("created_at <= ?");
            args.add(Timestamp.valueOf(filter.to()));
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static void add(List<String> clauses, List<Object> args, String clause, String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(clause);
            args.add(value.trim());
        }
    }

    public record Filter(String actor, String action, String targetType, String targetId, LocalDateTime from,
                         LocalDateTime to) { }
}
