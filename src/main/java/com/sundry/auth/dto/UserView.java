package com.sundry.auth.dto;

import com.sundry.user.Role;

/**
 * 对外返回的用户信息，刻意不包含 passwordHash。
 */
public record UserView(Long id, String phone, Role role) {
}
