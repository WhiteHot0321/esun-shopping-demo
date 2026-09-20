package com.esun.shop.service;

import com.esun.shop.dto.AuthResponse;
import com.esun.shop.dto.ChangePasswordRequest;
import com.esun.shop.dto.ForgotPasswordRequest;
import com.esun.shop.dto.LoginRequest;
import com.esun.shop.dto.RegisterRequest;
import com.esun.shop.dto.ResetPasswordRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.model.PasswordResetToken;
import com.esun.shop.repository.MemberRepository;
import com.esun.shop.repository.PasswordResetTokenRepository;
import com.esun.shop.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    // No SMTP/email-sending infrastructure exists yet (see docs/tasks/017-buyer-feature-list.md
    // #1.4); this is deliberately the "generate a one-time token" minimal version the task
    // describes, with the reset link logged instead of emailed. Wiring a real mail sender is a
    // follow-up, not part of this pass.
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final MemberRepository memberRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(MemberRepository memberRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            JwtService jwtService) {
        this.memberRepository = memberRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (memberRepository.findByEmail(email) != null) {
            throw new BusinessException("此 Email 已被註冊", HttpStatus.CONFLICT);
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());
        Member member = memberRepository.insert(email, passwordHash);

        String token = jwtService.generateToken(member.getEmail(), member.getRole());
        return new AuthResponse(token, member.getEmail(), member.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Member member = memberRepository.findByEmail(email);
        if (member == null || !passwordEncoder.matches(request.getPassword(), member.getPasswordHash())) {
            // Same message/status for "no such account" and "wrong password" - a distinct
            // "email not found" response would let a caller enumerate registered emails.
            throw new BusinessException("帳號或密碼錯誤", HttpStatus.UNAUTHORIZED);
        }

        String token = jwtService.generateToken(member.getEmail(), member.getRole());
        return new AuthResponse(token, member.getEmail(), member.getRole());
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Member member = memberRepository.findByEmail(email);
        // Always behave the same way whether or not the account exists - a different response
        // for "no such account" would let a caller enumerate registered emails, same defensive
        // reasoning as login()'s unified error message.
        if (member != null) {
            String rawToken = generateRawToken();
            LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(RESET_TOKEN_TTL);
            passwordResetTokenRepository.createForMember(member.getId(), hashToken(rawToken), expiresAt);
            log.info("已產生密碼重設權杖 member_id={}，30 分鐘後過期（尚無寄信服務，token 僅記錄於日誌）: {}",
                    member.getId(), rawToken);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findValidByTokenHashForUpdate(hashToken(request.getToken()));
        if (resetToken == null) {
            throw new BusinessException("重設連結無效或已過期", HttpStatus.BAD_REQUEST);
        }

        int consumed = passwordResetTokenRepository.markUsedIfUnusedAndUnexpired(resetToken.getId());
        if (consumed != 1) {
            throw new BusinessException("重設連結無效或已過期", HttpStatus.BAD_REQUEST);
        }

        memberRepository.updatePasswordHash(resetToken.getMemberId(), passwordEncoder.encode(request.getNewPassword()));
    }

    public void changePassword(String email, ChangePasswordRequest request) {
        Member member = email == null ? null : memberRepository.findByEmail(email);
        if (member == null || !passwordEncoder.matches(request.getCurrentPassword(), member.getPasswordHash())) {
            // 400, not 401: the caller's JWT is still valid (JwtAuthFilter already accepted it) -
            // this rejects the *current password field's value*, a request-validation failure, not
            // an authentication failure. api.js's response interceptor force-logs-out on any 401,
            // so reusing that status here would wrongly end the caller's session over a typo.
            throw new BusinessException("目前密碼錯誤", HttpStatus.BAD_REQUEST);
        }
        memberRepository.updatePasswordHash(member.getId(), passwordEncoder.encode(request.getNewPassword()));
    }

    /** 256-bit random token, hex-encoded so it's URL-safe as a query-string value. */
    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Only the SHA-256 hash of the reset token is persisted, so a leaked/dumped database row
     * cannot itself be replayed as a valid reset link - mirrors the pattern of never storing
     * plaintext passwords.
     */
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
