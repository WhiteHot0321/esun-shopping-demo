package com.esun.shop.security;

import com.esun.shop.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards {@code /api/**}: a valid {@code Authorization: Bearer <token>} header is required
 * except for the public routes listed in {@link #isPublic}. A plain {@code Filter}
 * (via {@link OncePerRequestFilter}, already on the classpath through spring-boot-starter-web -
 * no Spring Security dependency needed) rather than a {@code HandlerInterceptor} because it
 * needs to reject unauthenticated requests before Spring MVC does any argument resolution or
 * validation work, and to write the 401 body itself (interceptors run after the exception-handling
 * machinery is already wired up but still inside MVC's dispatch, filters run in front of it).
 *
 * Endpoint protection decision (see PR description for the full writeup):
 *  - Public: POST /api/auth/register, POST /api/auth/login, POST /api/auth/forgot-password,
 *    POST /api/auth/reset-password, GET /api/products/available, POST /api/support/ask,
 *    GET /uploads/products/{uuid}.{jpg|png|webp} (product images shown in the public catalog)
 *    (a shopper must be able to browse, ask product questions, log in, and recover a
 *    forgotten password before they have a token).
 *  - Protected: POST /api/products, POST /api/orders, POST /api/auth/change-password
 *    (state-changing actions that require knowing who the caller is).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "缺少登入憑證");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        try {
            JwtService.JwtIdentity identity = jwtService.extractIdentity(token);
            request.setAttribute("authenticatedEmail", identity.email());
            request.setAttribute("authenticatedRole", identity.role());
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT 驗證失敗: {}", ex.getMessage());
            unauthorized(response, "登入憑證無效或已過期");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublic(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (HttpMethod.POST.matches(request.getMethod())
                && (path.equals("/api/auth/register")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/forgot-password")
                || path.equals("/api/auth/reset-password")
                || path.equals("/api/support/ask"))) {
            return true;
        }
        // 商品圖片隨公開商品目錄顯示；限定為伺服器產生的「UUID.副檔名」單層檔名，任何含 .. 或子目錄的路徑都不公開。
        return HttpMethod.GET.matches(request.getMethod())
                && (path.equals("/api/products/available")
                || path.matches("/api/products/[^/]+/reviews")
                || path.matches("/uploads/products/[0-9a-f-]{36}\\.(jpg|png|webp)"));
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
