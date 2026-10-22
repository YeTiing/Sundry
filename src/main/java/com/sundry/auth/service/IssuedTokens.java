package com.sundry.auth.service;

import com.sundry.auth.dto.UserView;

public record IssuedTokens(String accessToken, long expiresInSeconds, String refreshToken, UserView user) {
}
