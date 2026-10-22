package com.sundry.auth.security;

import com.sundry.user.Role;

/**
 * JWT 过滤器写入 SecurityContext 的最小身份。
 * 角色来自数据库（不是令牌），所以管理员被降级后立即生效。
 */
public record AuthenticatedUser(Long id, Role role) {
}
