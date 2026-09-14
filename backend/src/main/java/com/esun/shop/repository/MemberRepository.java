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
}
