package com.esun.shop.repository;

import com.esun.shop.model.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class MemberRepository {
    public record Profile(String email, String displayName, String phone) {}

    private static final RowMapper<Member> MEMBER_ROW_MAPPER = (rs, rowNum) -> {
        Member m = new Member();
        m.setId(rs.getLong("id"));
        m.setEmail(rs.getString("email"));
        m.setPasswordHash(rs.getString("password_hash"));
        m.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return m;
    };

    private final JdbcTemplate jdbcTemplate;

    public MemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Member findByEmail(String email) {
        String sql = "SELECT id, email, password_hash, created_at FROM member WHERE email = ?";
        List<Member> list = jdbcTemplate.query(sql, MEMBER_ROW_MAPPER, email);
        return list.isEmpty() ? null : list.get(0);
    }

    public Member insert(String email, String passwordHash) {
        String sql = "INSERT INTO member (email, password_hash) VALUES (?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, passwordHash);
            return ps;
        }, keyHolder);

        Member member = new Member();
        member.setId(keyHolder.getKey().longValue());
        member.setEmail(email);
        member.setPasswordHash(passwordHash);
        return member;
    }

    public void updatePasswordHash(Long memberId, String passwordHash) {
        jdbcTemplate.update("UPDATE member SET password_hash = ? WHERE id = ?", passwordHash, memberId);
    }

    public Profile findProfileByEmail(String email) {
        String sql = "SELECT email, display_name, phone FROM member WHERE email = ?";
        List<Profile> profiles = jdbcTemplate.query(sql, (rs, rowNum) -> new Profile(
                rs.getString("email"), rs.getString("display_name"), rs.getString("phone")), email);
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public int updateProfile(String email, String displayName, String phone) {
        return jdbcTemplate.update(
                "UPDATE member SET display_name = ?, phone = ? WHERE email = ?",
                displayName, phone, email);
    }
}
