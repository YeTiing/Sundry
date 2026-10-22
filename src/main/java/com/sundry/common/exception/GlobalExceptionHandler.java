package com.sundry.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    // @PreAuthorize 拒绝时抛这个异常，不处理会被兜底成 500
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(new ApiError(ErrorCode.FORBIDDEN.name(), ErrorCode.FORBIDDEN.message()));
    }

    // 上传超过 Spring multipart 上限（还没进业务层就失败）
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.status())
                .body(new ApiError(ErrorCode.FILE_TOO_LARGE.name(), ErrorCode.FILE_TOO_LARGE.message()));
    }

    // multipart 里没有 file 字段
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.status(ErrorCode.FILE_EMPTY.status())
                .body(new ApiError(ErrorCode.FILE_EMPTY.name(), ErrorCode.FILE_EMPTY.message()));
    }

    // JSON 体格式错误或枚举值不认识
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(new ApiError(ErrorCode.VALIDATION_FAILED.name(), "请求体格式不正确"));
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
