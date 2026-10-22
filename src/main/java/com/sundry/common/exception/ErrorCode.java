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
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有访问权限"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    ROLE_CHANGE_FORBIDDEN(HttpStatus.CONFLICT, "不能修改超级管理员的角色"),
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "角色不合法"),
    FILE_EMPTY(HttpStatus.BAD_REQUEST, "文件不能为空"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5MB"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "只支持 JPEG、PNG、WebP 图片"),
    FILE_UPLOAD_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "文件上传失败，请稍后重试"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "商品不存在或未上架"),
    CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "分类不存在或已停用"),
    COVER_NOT_FOUND(HttpStatus.BAD_REQUEST, "封面图片不存在"),
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
