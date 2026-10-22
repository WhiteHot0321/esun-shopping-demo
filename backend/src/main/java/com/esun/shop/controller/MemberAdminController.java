package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.service.MemberAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Member management for maintainers (ADMIN only). Role/status changes revoke the target's tokens immediately. */
@Tag(name = "會員管理 Member Admin", description = "僅 ADMIN")
@RestController
public class MemberAdminController {
    private final MemberAdminService service;

    public MemberAdminController(MemberAdminService service) {
        this.service = service;
    }

    @Operation(summary = "查詢會員（email 前綴、角色、分頁）（ADMIN）")
    @GetMapping("/api/admin/members")
    public ApiResponse<MemberAdminService.MemberPage> search(@RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String role,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size,
                                                             HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(service.search(q, role, page, size));
    }

    @Operation(summary = "變更會員角色，舊 token 立即失效（ADMIN）")
    @PatchMapping("/api/admin/members/{id}/role")
    public ApiResponse<MemberAdminService.MemberView> changeRole(@PathVariable long id,
                                                                 @Valid @RequestBody ChangeRoleRequest body,
                                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.changeRole(requireAdmin(request), id, body.role()));
    }

    @Operation(summary = "停用會員，舊 token 立即失效（ADMIN）")
    @PostMapping("/api/admin/members/{id}/disable")
    public ApiResponse<MemberAdminService.MemberView> disable(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.setEnabled(requireAdmin(request), id, false));
    }

    @Operation(summary = "啟用會員（ADMIN）")
    @PostMapping("/api/admin/members/{id}/enable")
    public ApiResponse<MemberAdminService.MemberView> enable(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.setEnabled(requireAdmin(request), id, true));
    }

    public record ChangeRoleRequest(@NotNull(message = "請指定角色") Member.Role role) { }

    private static String requireAdmin(HttpServletRequest request) {
        if (!(request.getAttribute("authenticatedRole") instanceof Member.Role role) || role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        Object identity = request.getAttribute("authenticatedEmail");
        if (!(identity instanceof String email) || email.isBlank()) {
            throw new BusinessException("缺少登入憑證", HttpStatus.UNAUTHORIZED);
        }
        return email;
    }
}
