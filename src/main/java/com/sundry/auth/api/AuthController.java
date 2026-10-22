package com.sundry.auth.api;

import com.sundry.auth.dto.CodeLoginRequest;
import com.sundry.auth.dto.PasswordLoginRequest;
import com.sundry.auth.dto.RegisterRequest;
import com.sundry.auth.dto.SendCodeRequest;
import com.sundry.auth.dto.TokenResponse;
import com.sundry.auth.dto.UserView;
import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.auth.security.UserPrincipal;
import com.sundry.auth.service.AuthService;
import com.sundry.auth.service.IssuedTokens;
import com.sundry.auth.service.JwtProperties;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.user.User;
import com.sundry.user.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口。@RestController = @Controller + @ResponseBody，返回值直接序列化成 JSON；
 * @RequestMapping 给这一组接口加上 /api/auth 前缀。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, UserService userService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.userService = userService;
        this.jwtProperties = jwtProperties;
    }

    // @PostMapping 限定 POST；@ResponseStatus 固定返回 204（无响应体）
    // @Valid 触发 DTO 上的校验注解；@RequestBody 把 JSON 请求体反序列化成对象
    @PostMapping("/sms-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendCode(request.phone(), request.scene());
    }

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                                  HttpServletResponse response) {
        return tokenResponse(authService.register(request), response);
    }

    @PostMapping("/login/code")
    public ResponseEntity<TokenResponse> loginByCode(@Valid @RequestBody CodeLoginRequest request,
                                                     HttpServletResponse response) {
        return tokenResponse(authService.loginByCode(request), response);
    }

    @PostMapping("/login/password")
    public ResponseEntity<TokenResponse> loginByPassword(@Valid @RequestBody PasswordLoginRequest request,
                                                         HttpServletResponse response) {
        return tokenResponse(authService.loginByPassword(request), response);
    }

    // refresh token 从 HttpOnly Cookie 读取，前端拿不到明文
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        return tokenResponse(authService.refresh(readRefreshCookie(request)), response);
    }

    // 退出允许不带令牌调用（已在安全配置里放行），方便清理过期会话
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                       HttpServletRequest request, HttpServletResponse response) {
        String accessToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring("Bearer ".length()).trim();
        }
        authService.logout(accessToken, readRefreshCookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    // Authentication 由 Spring Security 注入：JWT 请求里是 AuthenticatedUser，密码登录时是 UserPrincipal
    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        Long userId;
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            userId = user.id();
        } else if (principal instanceof UserPrincipal user) {
            userId = user.id();
        } else {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userService.requireById(userId);
        return new UserView(user.getId(), user.getPhone());
    }

    private ResponseEntity<TokenResponse> tokenResponse(IssuedTokens tokens, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString());
        TokenResponse body = new TokenResponse(tokens.accessToken(), tokens.expiresInSeconds(), tokens.user());
        return ResponseEntity.ok(body);
    }

    /**
     * HttpOnly：JavaScript 读不到，降低 XSS 窃取风险；
     * SameSite=Lax：跨站 POST 不携带；Path=/api/auth：只在认证接口发送。
     */
    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(jwtProperties.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(jwtProperties.getRefreshTtl())
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(jwtProperties.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (jwtProperties.getRefreshCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
