package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.AuditAction;
import com.esun.shop.model.Member;
import com.esun.shop.repository.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-side member management (Phase 4.#3 / M0). Every mutation bumps {@code member.token_version}, which
 * {@code JwtAuthFilter} compares on each request, so the affected user's outstanding tokens die immediately.
 *
 * Concurrency: each mutation first locks all ACTIVE admins (id order) and then the target row. Two admins demoting or
 * disabling each other therefore serialize; the loser re-reads the actor after the lock and is refused, so the system
 * can never end with zero active admins.
 */
@Service
public class MemberAdminService {
    private static final int MAX_PAGE_SIZE = 100;

    public record MemberView(long id, String email, Member.Role role, Member.Status status, LocalDateTime createdAt) { }

    public record MemberPage(List<MemberView> items, long total, int page, int size) { }

    private final MemberRepository memberRepository;
    private final AuditLogService auditLogService;

    public MemberAdminService(MemberRepository memberRepository, AuditLogService auditLogService) {
        this.memberRepository = memberRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public MemberPage search(String emailPrefix, String role, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("分頁參數不合法", HttpStatus.BAD_REQUEST);
        }
        Member.Role roleFilter = null;
        if (role != null && !role.isBlank() && !"all".equalsIgnoreCase(role.trim())) {
            try {
                roleFilter = Member.Role.valueOf(role.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("角色不合法", HttpStatus.BAD_REQUEST);
            }
        }
        List<MemberView> items = memberRepository.search(emailPrefix, roleFilter, size, page * size).stream()
                .map(MemberAdminService::view).toList();
        return new MemberPage(items, memberRepository.count(emailPrefix, roleFilter), page, size);
    }

    @Transactional
    public MemberView changeRole(String actorEmail, long targetId, Member.Role newRole) {
        if (newRole == null) {
            throw new BusinessException("請指定角色", HttpStatus.BAD_REQUEST);
        }
        Member target = lockActorAndTarget(actorEmail, targetId);
        if (target.getRole() == newRole) {
            throw new BusinessException("角色未變更", HttpStatus.CONFLICT);
        }
        if (target.getRole() == Member.Role.ADMIN && target.getStatus() == Member.Status.ACTIVE
                && memberRepository.lockActiveAdmins() <= 1) {
            throw new BusinessException("不可移除最後一位管理員", HttpStatus.CONFLICT);
        }
        Map<String, Object> before = snapshot(target);
        memberRepository.updateRoleAndBumpVersion(targetId, newRole);
        Member after = memberRepository.findById(targetId);
        auditLogService.record(actorEmail, Member.Role.ADMIN, AuditAction.MEMBER_ROLE_CHANGE,
                String.valueOf(targetId), before, snapshot(after));
        return view(after);
    }

    @Transactional
    public MemberView setEnabled(String actorEmail, long targetId, boolean enabled) {
        Member target = lockActorAndTarget(actorEmail, targetId);
        Member.Status wanted = enabled ? Member.Status.ACTIVE : Member.Status.DISABLED;
        if (target.getStatus() == wanted) {
            throw new BusinessException(enabled ? "帳號已是啟用狀態" : "帳號已是停用狀態", HttpStatus.CONFLICT);
        }
        if (!enabled && target.getRole() == Member.Role.ADMIN && memberRepository.lockActiveAdmins() <= 1) {
            throw new BusinessException("不可停用最後一位管理員", HttpStatus.CONFLICT);
        }
        Map<String, Object> before = snapshot(target);
        memberRepository.updateStatusAndBumpVersion(targetId, wanted);
        Member after = memberRepository.findById(targetId);
        auditLogService.record(actorEmail, Member.Role.ADMIN,
                enabled ? AuditAction.MEMBER_ENABLE : AuditAction.MEMBER_DISABLE,
                String.valueOf(targetId), before, snapshot(after));
        return view(after);
    }

    /**
     * Locks the active-admin set, then the target, and returns the locked target. The actor is re-read after the
     * lock: a concurrent admin may have demoted or disabled them while this request waited, in which case it must be
     * refused (this is what keeps two admins from demoting each other into an admin-less system).
     */
    private Member lockActorAndTarget(String actorEmail, long targetId) {
        memberRepository.lockActiveAdmins();
        Member actor = actorEmail == null ? null : memberRepository.findByEmail(actorEmail);
        if (actor == null || actor.getRole() != Member.Role.ADMIN || actor.getStatus() != Member.Status.ACTIVE) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        if (actor.getId() == targetId) {
            throw new BusinessException("不可對自己的帳號執行此操作", HttpStatus.CONFLICT);
        }
        Member target = memberRepository.findByIdForUpdate(targetId);
        if (target == null) {
            throw new BusinessException("找不到會員", HttpStatus.NOT_FOUND);
        }
        return target;
    }

    /** Whitelisted fields only - never the password hash. */
    private static Map<String, Object> snapshot(Member m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("email", m.getEmail());
        map.put("role", m.getRole().name());
        map.put("status", m.getStatus().name());
        return map;
    }

    private static MemberView view(Member m) {
        return new MemberView(m.getId(), m.getEmail(), m.getRole(), m.getStatus(), m.getCreatedAt());
    }
}
