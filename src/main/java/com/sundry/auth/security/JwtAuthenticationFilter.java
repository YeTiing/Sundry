package com.sundry.auth.security;

import com.sundry.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 每个请求执行一次：从 Authorization: Bearer xxx 解析 JWT，成功就写入 SecurityContext。
 * @Component 注册为 Bean；OncePerRequestFilter 保证一次请求只执行一次。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 没有 Authorization 头就放行，是否需要登录交给后面的授权规则判断
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                // decode 会校验签名、exp 和 issuer；再查黑名单，退出后的令牌不放行
                Jwt jwt = jwtService.decode(token);
                if (!jwtService.isBlacklisted(jwt.getId())) {
                    Long userId = Long.valueOf(jwt.getSubject());
                    // 认证对象只带用户 ID；权限列表为空（目前还没有角色体系）
                    UsernamePasswordAuthenticationToken authentication =
                            UsernamePasswordAuthenticationToken.authenticated(new AuthenticatedUser(userId), null, List.of());
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // 令牌无效不在这里抛异常，清空上下文后交给入口点统一返回 401
                SecurityContextHolder.clearContext();
                log.debug("invalid access token: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
