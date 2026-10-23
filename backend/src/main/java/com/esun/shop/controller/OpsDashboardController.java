package com.esun.shop.controller;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.OpsDashboardView;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.service.OpsDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations dashboard for maintainers (ADMIN only). Read-only; figures may be a few seconds old. */
@Tag(name = "營運儀表板 Ops Dashboard", description = "僅 ADMIN")
@RestController
public class OpsDashboardController {
    private final OpsDashboardService service;

    public OpsDashboardController(OpsDashboardService service) {
        this.service = service;
    }

    @Operation(summary = "營運儀表板：今日訂單／營收、付款異常、低庫存、Redis 狀態（ADMIN）")
    @GetMapping("/api/admin/dashboard")
    public ApiResponse<OpsDashboardView> dashboard(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(service.get());
    }

    private static void requireAdmin(HttpServletRequest request) {
        if (!(request.getAttribute("authenticatedRole") instanceof Member.Role role) || role != Member.Role.ADMIN) {
            throw new BusinessException("權限不足", HttpStatus.FORBIDDEN);
        }
    }
}
