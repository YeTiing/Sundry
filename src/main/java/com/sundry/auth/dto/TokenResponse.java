package com.sundry.auth.dto;

public record TokenResponse(String accessToken, long expiresIn, UserView user) {
}
