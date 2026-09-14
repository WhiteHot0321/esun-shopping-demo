package com.esun.shop.service;

import com.esun.shop.dto.AuthResponse;
import com.esun.shop.dto.LoginRequest;
import com.esun.shop.dto.RegisterRequest;
import com.esun.shop.exception.BusinessException;
import com.esun.shop.model.Member;
import com.esun.shop.repository.MemberRepository;
import com.esun.shop.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService}. MemberRepository and JwtService are mocked so
 * these exercise only the service's own logic (duplicate-email check, password
 * hashing/verification, email normalization) - mirrors ProductServiceTest/OrderServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(memberRepository, jwtService);
    }

    @Test
    void register_newEmail_hashesPasswordAndReturnsToken() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("User@Example.com");
        request.setPassword("s3cret-pw");

        when(memberRepository.findByEmail("user@example.com")).thenReturn(null);
        Member saved = new Member();
        saved.setId(1L);
        saved.setEmail("user@example.com");
        when(memberRepository.insert(eq("user@example.com"), anyString())).thenReturn(saved);
        when(jwtService.generateToken("user@example.com")).thenReturn("jwt-token");

        AuthResponse response = authService.register(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getEmail()).isEqualTo("user@example.com");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(memberRepository).insert(eq("user@example.com"), hashCaptor.capture());
        // Password is BCrypt-hashed, never stored in plaintext.
        assertThat(hashCaptor.getValue()).isNotEqualTo("s3cret-pw");
        assertThat(BCrypt.checkpw("s3cret-pw", hashCaptor.getValue())).isTrue();
    }

    @Test
    void register_duplicateEmail_returns409AndDoesNotInsert() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("dup@example.com");
        request.setPassword("password123");

        Member existing = new Member();
        existing.setEmail("dup@example.com");
        when(memberRepository.findByEmail("dup@example.com")).thenReturn(existing);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(memberRepository, never()).insert(anyString(), anyString());
    }

    @Test
    void login_correctPassword_returnsToken() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("correct-pw");

        Member member = new Member();
        member.setEmail("user@example.com");
        member.setPasswordHash(BCrypt.hashpw("correct-pw", BCrypt.gensalt()));
        when(memberRepository.findByEmail("user@example.com")).thenReturn(member);
        when(jwtService.generateToken("user@example.com")).thenReturn("jwt-token");

        AuthResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void login_wrongPassword_returns401() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("wrong-pw");

        Member member = new Member();
        member.setEmail("user@example.com");
        member.setPasswordHash(BCrypt.hashpw("correct-pw", BCrypt.gensalt()));
        when(memberRepository.findByEmail("user@example.com")).thenReturn(member);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_unknownEmail_returns401SameAsWrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setEmail("nobody@example.com");
        request.setPassword("whatever");

        when(memberRepository.findByEmail("nobody@example.com")).thenReturn(null);

        // Same status/message as a wrong password, to avoid leaking which emails are registered.
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
