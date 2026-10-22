package com.sundry.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 配置。@ConfigurationProperties(prefix="app.jwt") 把 app.jwt.* 绑定到字段，@Component 注册为 Bean。
 */
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;                                          // Base64 密钥，至少 32 字节
    private String issuer = "sundry";                               // 写入并校验 iss
    private Duration accessTtl = Duration.ofMinutes(30);            // access token 有效期
    private Duration refreshTtl = Duration.ofDays(7);               // refresh token 有效期
    private boolean refreshCookieSecure = false;                    // 生产环境走 HTTPS 时应为 true
    private String refreshCookieName = "sundry_refresh";            // HttpOnly Cookie 名称

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public boolean isRefreshCookieSecure() {
        return refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }
}
