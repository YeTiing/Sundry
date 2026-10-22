package com.sundry.admin.dto;

import com.sundry.user.Role;
import com.sundry.user.User;

import java.time.LocalDateTime;

/**
 * 后台用户列表项，不含密码哈希。
 */
public record AdminUserView(Long id, String phone, Role role, boolean active, LocalDateTime createdAt) {

    public static AdminUserView from(User user) {
        return new AdminUserView(user.getId(), user.getPhone(), user.getRole(),
                user.isActive(), user.getCreatedAt());
    }
}
