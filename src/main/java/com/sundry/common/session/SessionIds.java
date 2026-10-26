package com.sundry.common.session;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;

import java.util.regex.Pattern;

/**
 * 会话标识只允许安全字符，避免拼进 Redis key 时污染命名空间。
 */
public final class SessionIds {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private SessionIds() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!SAFE.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "会话标识不合法");
        }
        return value;
    }
}
