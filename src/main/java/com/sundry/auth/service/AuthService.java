package com.sundry.auth.service;

import com.sundry.auth.dto.CodeLoginRequest;
import com.sundry.auth.dto.PasswordLoginRequest;
import com.sundry.auth.dto.RegisterRequest;
import com.sundry.auth.dto.UserView;
import com.sundry.auth.security.UserPrincipal;
import com.sundry.auth.sms.SmsScene;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
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

    public AuthService(UserService userService,
                       SmsCodeService smsCodeService,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties) {
        this.userService = userService;
        this.smsCodeService = smsCodeService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
    }

    public void sendCode(String phone, SmsScene scene) {
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
        if (userService.findByPhone(request.phone()) != null) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        smsCodeService.verifyAndConsume(request.phone(), SmsScene.REGISTER, request.code());
        User user = userService.create(request.phone(), passwordEncoder.encode(request.password()));
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
                new UserView(user.getId(), user.getPhone()));
    }
}
