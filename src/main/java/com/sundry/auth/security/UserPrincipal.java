package com.sundry.auth.security;

import com.sundry.user.Role;
import com.sundry.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * UserDetails 是 Spring Security 对“登录用户”的抽象；这里用 record 实现。
 * 密码只在这一层交给框架比对，不会出现在任何接口响应里。
 */
public record UserPrincipal(Long id, String phone, String passwordHash, boolean active, Role role)
        implements UserDetails {

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getPhone(), user.getPasswordHash(),
                user.isActive(), user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security 的 hasRole('ADMIN') 会自动补 ROLE_ 前缀
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return phone;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    // 返回 false 时 Spring Security 会抛 DisabledException，登录直接失败
    @Override
    public boolean isEnabled() {
        return active;
    }
}
