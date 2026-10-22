package com.sundry.auth.dto;

/**
 * 登录 / 刷新成功后的响应体；refresh token 只在 Set-Cookie 里，不放进这里。
 */
public record TokenResponse(String accessToken, long expiresIn, UserView user) {
}
