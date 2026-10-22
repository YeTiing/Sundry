package com.sundry.auth.security;

/**
 * JWT 过滤器写入 SecurityContext 的最小身份，只含用户 ID。
 * 需要更多信息时按 ID 查库，不信任令牌里的其他字段。
 */
public record AuthenticatedUser(Long id) {
}
