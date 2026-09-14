package com.esun.shop.service;

import com.esun.shop.dto.AuthResponse;
import com.esun.shop.dto.LoginRequest;
import com.esun.shop.dto.RegisterRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.repository.MemberRepository;
import com.esun.shop.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final MemberRepository memberRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(MemberRepository memberRepository, JwtService jwtService) {
        this.memberRepository = memberRepository;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (memberRepository.findByEmail(email) != null) {
            throw new BusinessException("此 Email 已被註冊", HttpStatus.CONFLICT);
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());
        Member member = memberRepository.insert(email, passwordHash);

        String token = jwtService.generateToken(member.getEmail());
        return new AuthResponse(token, member.getEmail());
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Member member = memberRepository.findByEmail(email);
        if (member == null || !passwordEncoder.matches(request.getPassword(), member.getPasswordHash())) {
            // Same message/status for "no such account" and "wrong password" - a distinct
            // "email not found" response would let a caller enumerate registered emails.
            throw new BusinessException("帳號或密碼錯誤", HttpStatus.UNAUTHORIZED);
        }

        String token = jwtService.generateToken(member.getEmail());
        return new AuthResponse(token, member.getEmail());
    }
}
