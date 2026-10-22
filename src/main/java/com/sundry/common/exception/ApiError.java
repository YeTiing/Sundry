package com.sundry.common.exception;

/**
 * 统一错误响应体。record 会自动生成构造器和 code() / message() 访问器。
 */
public record ApiError(String code, String message) {
}
