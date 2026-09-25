package com.esun.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.esun.shop.dto.ApiResponse;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "會員資料 Member Profile", description = "登入會員自己的資料")
@RestController
@RequestMapping("/api/member/profile")
public class MemberProfileController {
    private final MemberRepository memberRepository;

    public MemberProfileController(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Operation(summary = "取得我的會員資料")
    @GetMapping
    public ApiResponse<MemberRepository.Profile> getProfile(HttpServletRequest request) {
        return ApiResponse.ok(requireProfile(authenticatedEmail(request)));
    }

    @Operation(summary = "更新我的會員資料")
    @PutMapping
    public ApiResponse<MemberRepository.Profile> updateProfile(
            @Valid @RequestBody UpdateProfileRequest profile, HttpServletRequest request) {
        String email = authenticatedEmail(request);
        int updated = memberRepository.updateProfile(
                email, trimToNull(profile.displayName()), trimToNull(profile.phone()));
        if (updated != 1) {
            throw new BusinessException("會員不存在", HttpStatus.NOT_FOUND);
        }
        return ApiResponse.ok(requireProfile(email));
    }

    private MemberRepository.Profile requireProfile(String email) {
        MemberRepository.Profile profile = memberRepository.findProfileByEmail(email);
        if (profile == null) {
            throw new BusinessException("會員不存在", HttpStatus.NOT_FOUND);
        }
        return profile;
    }

    private String authenticatedEmail(HttpServletRequest request) {
        return (String) request.getAttribute("authenticatedEmail");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record UpdateProfileRequest(
            @Size(max = 100, message = "顯示名稱不可超過 100 個字") String displayName,
            @Size(max = 30, message = "電話不可超過 30 個字")
            @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "電話格式不正確") String phone) {}
}
