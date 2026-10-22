package com.sundry.auth.security;

import com.sundry.user.User;
import com.sundry.user.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 密码登录时用它按用户名加载用户；本项目用户名就是手机号。
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    public UserDetailsServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        User user = userMapper.findByPhone(phone);
        if (user == null) {
            // 抛这个异常后，认证管理器会统一转成“手机号或密码错误”，不暴露手机号是否存在
            throw new UsernameNotFoundException("用户不存在");
        }
        return UserPrincipal.from(user);
    }
}
