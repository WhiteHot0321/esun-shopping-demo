package com.esun.shop.repository;

import com.esun.shop.model.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MemberRepository {
    public record Profile(String email, String displayName, String phone) {}

    /** What {@code JwtAuthFilter} checks on every request to decide whether a still-signed token is still honoured. */
    public record AuthState(Member.Status status, int tokenVersion) {}

    private static final String MEMBER_COLUMNS = "id, email, password_hash, role, status, token_version, created_at";

    private static final RowMapper<Member> MEMBER_ROW_MAPPER = (rs, rowNum) -> {
        Member m = new Member();
        m.setId(rs.getLong("id"));
        m.setEmail(rs.getString("email"));
        m.setPasswordHash(rs.getString("password_hash"));
        m.setRole(Member.Role.valueOf(rs.getString("role")));
        m.setStatus(Member.Status.valueOf(rs.getString("status")));
        m.setTokenVersion(rs.getInt("token_version"));
        m.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return m;
    };

    private final JdbcTemplate jdbcTemplate;

    public MemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Member findByEmail(String email) {
        String sql = "SELECT id, email, password_hash, role, status, token_version, created_at FROM member WHERE email = ?";
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
        member.setRole(Member.Role.BUYER);
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

    /** Indexed lookup on the unique email key; null if the account no longer exists. */
    public AuthState findAuthState(String email) {
        List<AuthState> list = jdbcTemplate.query("SELECT status, token_version FROM member WHERE email = ?",
                (rs, rowNum) -> new AuthState(Member.Status.valueOf(rs.getString("status")), rs.getInt("token_version")),
                email);
        return list.isEmpty() ? null : list.get(0);
    }

    public Member findById(long id) {
        List<Member> list = jdbcTemplate.query("SELECT " + MEMBER_COLUMNS + " FROM member WHERE id = ?",
                MEMBER_ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Row lock (FOR UPDATE); must run inside a transaction. */
    public Member findByIdForUpdate(long id) {
        List<Member> list = jdbcTemplate.query("SELECT " + MEMBER_COLUMNS + " FROM member WHERE id = ? FOR UPDATE",
                MEMBER_ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Locks every ACTIVE admin in id order and returns how many there are. Every writer that can shrink the admin
     * set takes this lock first, so concurrent demotions serialize instead of both seeing "another admin exists".
     */
    public int lockActiveAdmins() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM member WHERE role = 'ADMIN' AND status = 'ACTIVE' ORDER BY id FOR UPDATE").size();
    }

    public int updateRoleAndBumpVersion(long id, Member.Role role) {
        return jdbcTemplate.update("UPDATE member SET role = ?, token_version = token_version + 1 WHERE id = ?",
                role.name(), id);
    }

    public int updateStatusAndBumpVersion(long id, Member.Status status) {
        return jdbcTemplate.update("UPDATE member SET status = ?, token_version = token_version + 1 WHERE id = ?",
                status.name(), id);
    }

    public List<Member> search(String emailPrefix, Member.Role role, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + MEMBER_COLUMNS + " FROM member WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, emailPrefix, role);
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MEMBER_ROW_MAPPER, args.toArray());
    }

    public long count(String emailPrefix, Member.Role role) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM member WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, emailPrefix, role);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private static void appendFilter(StringBuilder sql, List<Object> args, String emailPrefix, Member.Role role) {
        if (emailPrefix != null && !emailPrefix.isBlank()) {
            // Prefix match keeps the unique email index usable; LIKE metacharacters in the input are escaped.
            sql.append(" AND email LIKE ? ESCAPE '!'");
            args.add(emailPrefix.trim().toLowerCase()
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (role != null) {
            sql.append(" AND role = ?");
            args.add(role.name());
        }
    }
}
