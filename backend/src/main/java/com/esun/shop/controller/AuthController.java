package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.dto.AuthResponse;
import com.esun.shop.dto.ChangePasswordRequest;
import com.esun.shop.dto.ForgotPasswordRequest;
import com.esun.shop.dto.LoginRequest;
import com.esun.shop.dto.RegisterRequest;
import com.esun.shop.dto.ResetPasswordRequest;
import com.esun.shop.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "認證 Auth", description = "註冊、登入、密碼重設")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "註冊新會員（公開）")
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @Operation(summary = "登入並取得 JWT（公開）")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(summary = "申請密碼重設（公開）")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "以重設 token 設定新密碼（公開）")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "變更目前登入會員的密碼")
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        String email = (String) httpRequest.getAttribute("authenticatedEmail");
        authService.changePassword(email, request);
        return ApiResponse.ok(null);
    }
}
