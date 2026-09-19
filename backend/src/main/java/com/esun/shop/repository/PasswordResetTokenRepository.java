package com.esun.shop.repository;

import com.esun.shop.model.PasswordResetToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class PasswordResetTokenRepository {
    private static final RowMapper<PasswordResetToken> ROW_MAPPER = (rs, rowNum) -> {
        PasswordResetToken t = new PasswordResetToken();
        t.setId(rs.getLong("id"));
        t.setMemberId(rs.getLong("member_id"));
        t.setTokenHash(rs.getString("token_hash"));
        t.setExpiresAt(rs.getTimestamp("expires_at").toLocalDateTime());
        Timestamp usedAt = rs.getTimestamp("used_at");
        t.setUsedAt(usedAt == null ? null : usedAt.toLocalDateTime());
        t.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return t;
    };

    private final JdbcTemplate jdbcTemplate;

    public PasswordResetTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Any earlier unused tokens for this member are superseded so at most one stays valid. */
    public PasswordResetToken createForMember(Long memberId, String tokenHash, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "UPDATE password_reset_token SET used_at = UTC_TIMESTAMP() WHERE member_id = ? AND used_at IS NULL",
                memberId);

        String sql = "INSERT INTO password_reset_token (member_id, token_hash, expires_at) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, memberId);
            ps.setString(2, tokenHash);
            ps.setTimestamp(3, Timestamp.valueOf(expiresAt));
            return ps;
        }, keyHolder);

        PasswordResetToken token = new PasswordResetToken();
        token.setId(keyHolder.getKey().longValue());
        token.setMemberId(memberId);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(expiresAt);
        return token;
    }

    /**
     * Locks a currently valid token until the surrounding transaction finishes. This makes
     * validation and consumption one database-critical section: a concurrent reset using the
     * same token waits here and then observes the committed {@code used_at} value.
     */
    public PasswordResetToken findValidByTokenHashForUpdate(String tokenHash) {
        String sql = "SELECT id, member_id, token_hash, expires_at, used_at, created_at FROM password_reset_token "
                + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > UTC_TIMESTAMP() FOR UPDATE";
        List<PasswordResetToken> list = jdbcTemplate.query(sql, ROW_MAPPER, tokenHash);
        return list.isEmpty() ? null : list.get(0);
    }

    public int markUsedIfUnusedAndUnexpired(Long id) {
        return jdbcTemplate.update(
                "UPDATE password_reset_token SET used_at = UTC_TIMESTAMP() "
                        + "WHERE id = ? AND used_at IS NULL AND expires_at > UTC_TIMESTAMP()",
                id);
    }
}
