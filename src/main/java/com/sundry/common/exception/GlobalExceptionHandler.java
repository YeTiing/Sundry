package com.sundry.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常出口。@RestControllerAdvice 让这些 @ExceptionHandler 对所有 Controller 生效，
 * 返回的对象会被直接序列化成 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 业务异常携带 ErrorCode，直接决定 HTTP 状态码和文案
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.status())
                .body(new ApiError(code.name(), ex.getMessage()));
    }

    // @Valid 校验失败时 Spring 抛这个异常；只取第一条字段错误返回
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_FAILED.message());
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(ErrorCode.VALIDATION_FAILED.message());
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(ErrorCode.INVALID_CREDENTIALS.status())
                .body(new ApiError(ErrorCode.INVALID_CREDENTIALS.name(),
                        ErrorCode.INVALID_CREDENTIALS.message()));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException ex) {
        return ResponseEntity.status(ErrorCode.ACCOUNT_DISABLED.status())
                .body(new ApiError(ErrorCode.ACCOUNT_DISABLED.name(),
                        ErrorCode.ACCOUNT_DISABLED.message()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status())
                .body(new ApiError(ErrorCode.UNAUTHORIZED.name(),
                        ErrorCode.UNAUTHORIZED.message()));
    }

    // 兜底：未预料的异常只返回通用文案，堆栈仅写服务端日志，不泄露给客户端
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.name(),
                        ErrorCode.INTERNAL_ERROR.message()));
    }
}
