package com.sundry.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全相关配置。超级管理员用手机号指定，不通过接口授予。
 */
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private String superAdminPhone;

    public String getSuperAdminPhone() {
        return superAdminPhone;
    }

    public void setSuperAdminPhone(String superAdminPhone) {
        this.superAdminPhone = superAdminPhone;
    }
}
