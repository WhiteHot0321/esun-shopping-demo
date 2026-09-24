package com.esun.shop.service;

import com.esun.shop.dto.AuditLogPageResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.AuditAction;
import com.esun.shop.model.Member;
import com.esun.shop.repository.AuditLogRepository;
import com.esun.shop.repository.MemberRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Operation audit log (Phase 3.1 #12).
 *
 * {@link #record} deliberately has no transaction of its own: callers invoke it inside the same transaction as the
 * change it describes, so the audit row commits or rolls back together with that change. A write that succeeded
 * without its audit row (or an audit row for a write that rolled back) would make the trail untrustworthy.
 * Snapshots must never contain secrets (password hashes, tokens); callers pass only whitelisted business fields.
 */
@Service
public class AuditLogService {
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository repository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository repository, MemberRepository memberRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param role the actor's verified role, or null to resolve it from the member table
     * @param before state before the change (null for creations), serialised as JSON
     * @param after state after the change
     */
    public void record(String actor, Member.Role role, AuditAction action, String targetId, Object before, Object after) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        repository.insert(actor, roleName(actor, role), action.name(), action.targetType(), targetId,
                toJson(before), toJson(after));
    }

    public AuditLogPageResponse search(String actor, String action, String targetType, String targetId, String from,
                                       String to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
        if (action != null && !action.isBlank()) {
            try {
                AuditAction.valueOf(action.trim());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("稽核動作不合法", HttpStatus.BAD_REQUEST);
            }
        }
        AuditLogRepository.Filter filter = new AuditLogRepository.Filter(actor, action, targetType, targetId,
                parseTime(from), parseTime(to));
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BusinessException("起始時間不可晚於結束時間", HttpStatus.BAD_REQUEST);
        }
        return new AuditLogPageResponse(repository.search(filter, size, page * size), repository.count(filter), page, size);
    }

    /** Looks the actor's current role up once so batch writers can pass it to {@link #record} for every row. */
    public Member.Role roleOf(String actor) {
        Member member = actor == null ? null : memberRepository.findByEmail(actor);
        return member == null ? null : member.getRole();
    }

    private String roleName(String actor, Member.Role role) {
        Member.Role resolved = role != null ? role : roleOf(actor);
        return resolved != null ? resolved.name() : "UNKNOWN";
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("無法序列化稽核內容", ex);
        }
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("時間格式需為 ISO-8601（例如 2026-09-24T10:00:00）", HttpStatus.BAD_REQUEST);
        }
    }
}
