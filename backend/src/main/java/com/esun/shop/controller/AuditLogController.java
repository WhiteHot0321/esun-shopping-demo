package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.AuditLogPageResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit trail for maintainers (ADMIN). There is intentionally no write/update/delete endpoint. */
@Tag(name = "稽核日誌 Audit Log", description = "僅 ADMIN")
@RestController
public class AuditLogController {
    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "查詢操作稽核日誌（ADMIN）")
    @GetMapping("/api/admin/audit-logs")
    public ApiResponse<AuditLogPageResponse> search(@RequestParam(required = false) String actor,
                                                    @RequestParam(required = false) String action,
                                                    @RequestParam(required = false) String targetType,
                                                    @RequestParam(required = false) String targetId,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        if (!(request.getAttribute("authenticatedRole") instanceof Member.Role role) || role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
        return ApiResponse.ok(auditLogService.search(actor, action, targetType, targetId, from, to, page, size));
    }
}
