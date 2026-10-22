package com.sundry.user;

/**
 * 单商户下的角色。SUPER_ADMIN 只能由配置指定，不能通过接口授予。
 */
public enum Role {
    SUPER_ADMIN,
    ADMIN,
    USER
}
