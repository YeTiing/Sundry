package com.sundry.auth.service;

import com.sundry.auth.dto.UserView;

/**
 * 服务层返回的令牌组合（record 是不可变数据载体）。
 * refresh token 不放进响应体，由 Controller 写进 HttpOnly Cookie。
 */
public record IssuedTokens(String accessToken, long expiresInSeconds, String refreshToken, UserView user) {
}
