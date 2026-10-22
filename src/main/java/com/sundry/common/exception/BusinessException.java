package com.sundry.common.exception;

/**
 * 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 统一转成 HTTP 响应。
 * 继承 RuntimeException，业务方法就不用到处声明 throws。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
