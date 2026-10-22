package com.sundry.auth.security;

import com.sundry.user.User;
import com.sundry.user.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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
            throw new UsernameNotFoundException("用户不存在");
        }
        return UserPrincipal.from(user);
    }
}
