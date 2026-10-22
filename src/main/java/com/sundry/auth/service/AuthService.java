package com.sundry.auth.service;

import com.sundry.auth.dto.CodeLoginRequest;
import com.sundry.auth.dto.PasswordLoginRequest;
import com.sundry.auth.dto.RegisterRequest;
import com.sundry.auth.dto.UserView;
import com.sundry.auth.security.SecurityProperties;
import com.sundry.auth.security.UserPrincipal;
import com.sundry.auth.sms.SmsScene;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.user.Role;
import com.sundry.user.User;
import com.sundry.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * 认证业务编排。@Service 注册为 Bean，所有依赖通过构造器注入（便于测试替换）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final SmsCodeService smsCodeService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final SecurityProperties securityProperties;

    public AuthService(UserService userService,
                       SmsCodeService smsCodeService,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       SecurityProperties securityProperties) {
        this.userService = userService;
        this.smsCodeService = smsCodeService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.securityProperties = securityProperties;
    }

    public void sendCode(String phone, SmsScene scene) {
        // 发码前先检查手机号状态：注册场景不能已注册，登录场景必须已存在
        User user = userService.findByPhone(phone);

        if (scene == SmsScene.REGISTER && user != null) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        if (scene == SmsScene.LOGIN && user == null) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        smsCodeService.send(phone, scene);
    }

    public IssuedTokens register(RegisterRequest request) {
        // 先查重，避免用户输完验证码才发现手机号已注册；并发注册由数据库唯一索引兜底
        if (userService.findByPhone(request.phone()) != null) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        smsCodeService.verifyAndConsume(request.phone(), SmsScene.REGISTER, request.code());
        // 密码只存 BCrypt 哈希；encode 每次都会带随机盐，相同密码的哈希也不同
        Role role = request.phone().equals(securityProperties.getSuperAdminPhone())
                ? Role.SUPER_ADMIN
                : Role.USER;
        User user = userService.create(request.phone(), passwordEncoder.encode(request.password()), role);
        return issueTokens(user);
    }

    public IssuedTokens loginByCode(CodeLoginRequest request) {
        smsCodeService.verifyAndConsume(request.phone(), SmsScene.LOGIN, request.code());
        User user = userService.findByPhone(request.phone());
        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        requireActive(user);
        return issueTokens(user);
    }

    public IssuedTokens loginByPassword(PasswordLoginRequest request) {
        // 交给 Spring Security 的认证管理器：按手机号加载用户 + BCrypt 比对，失败抛 BadCredentialsException
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.phone(), request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userService.requireById(principal.id());
        requireActive(user);
        return issueTokens(user);
    }

    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        // consume 成功即作废旧的 refresh token，随后发一对新令牌（轮换）
        Long userId = refreshTokenService.consume(refreshToken);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        User user = userService.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        requireActive(user);
        return issueTokens(user);
    }

    public void logout(String accessToken, String refreshToken) {
        // 退出：作废 refresh token；access token 还没过期就加入黑名单
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                Jwt jwt = jwtService.decode(accessToken);
                jwtService.blacklist(jwt.getId(), jwt.getExpiresAt());
            } catch (RuntimeException ex) {
                log.debug("logout with invalid access token: {}", ex.getMessage());
            }
        }
    }

    private void requireActive(User user) {
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    private IssuedTokens issueTokens(User user) {
        JwtService.IssuedAccessToken accessToken = jwtService.issueAccessToken(user.getId());
        String refreshToken = refreshTokenService.issue(user.getId());
        long expiresIn = jwtProperties.getAccessTtl().toSeconds();
        return new IssuedTokens(accessToken.token(), expiresIn, refreshToken,
                new UserView(user.getId(), user.getPhone(), user.getRole()));
    }
}
