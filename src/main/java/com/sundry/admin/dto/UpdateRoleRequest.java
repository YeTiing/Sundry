package com.sundry.admin.dto;

import com.sundry.user.Role;
import jakarta.validation.constraints.NotNull;

/**
 * 超级管理员调整普通用户角色；SUPER_ADMIN 不在这里授予。
 */
public record UpdateRoleRequest(
        @NotNull(message = "角色不能为空")
        Role role) {
}
