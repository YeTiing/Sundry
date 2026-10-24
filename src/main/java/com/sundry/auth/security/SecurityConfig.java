package com.sundry.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置。@Configuration 表示这是配置类；@Bean 方法返回的对象交给容器管理。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String UNAUTHORIZED_BODY = "{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已过期\"}";
    private static final String FORBIDDEN_BODY = "{\"code\":\"FORBIDDEN\",\"message\":\"没有访问权限\"}";

    // 密码编码器：BCrypt 自带随机盐，同一个密码每次哈希结果都不同
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 密码登录用的认证管理器：按手机号加载用户，再用 BCrypt 比对密码
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsServiceImpl userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    // 安全过滤器链：所有 HTTP 请求都会经过这里
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())        // 无状态 API + Bearer token，不需要 CSRF token
                .cors(cors -> cors.disable())        // 前后端同源（开发靠 Vite 代理），暂不开 CORS
                .formLogin(form -> form.disable())   // 关掉 Spring 默认登录页
                .httpBasic(basic -> basic.disable()) // 关掉 Basic 认证
                .logout(logout -> logout.disable())  // 用自定义的 /api/auth/logout
                // 不创建 HttpSession，每个请求都靠 JWT 识别身份
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 白名单：登录注册和健康检查不需要令牌，其余请求默认要求已认证
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/sms-code",
                                "/api/auth/register",
                                "/api/auth/login/**",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/products/**",
                                "/api/categories",
                                "/api/shop",
                                "/actuator/health",
                                "/actuator/prometheus")
                        .permitAll()
                        // 秒杀只开放查询，下单接口仍然要求登录
                        .requestMatchers(HttpMethod.GET,
                                "/api/seckill/products/**",
                                "/api/seckill/activities/**")
                        .permitAll()
                        .anyRequest().authenticated())
                // 未登录 / 无权限时也返回 JSON，避免默认的 HTML 错误页
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_BODY))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_BODY)))
                // 把 JWT 过滤器插到用户名密码过滤器之前，先尝试解析令牌
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
