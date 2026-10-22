package com.sundry.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码：每个枚举同时携带 HTTP 状态和默认文案，异常处理器直接用它组装响应。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    INVALID_PHONE(HttpStatus.BAD_REQUEST, "手机号格式不正确"),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "手机号已注册"),
    SMS_SEND_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试"),
    SMS_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "今日验证码发送次数已达上限"),
    SMS_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "验证码已过期，请重新获取"),
    SMS_CODE_INVALID(HttpStatus.BAD_REQUEST, "验证码不正确"),
    SMS_CODE_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST, "验证码错误次数过多，请重新获取"),
    SMS_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "验证码发送失败，请稍后重试"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "手机号或密码错误"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已被禁用"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
