package com.sundry.auth.sms;

/**
 * 验证码用途。注册码和登录码在 Redis 里分开存，不能混用。
 */
public enum SmsScene {
    REGISTER,
    LOGIN
}
