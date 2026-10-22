package com.sundry.auth.security;

import com.sundry.user.Role;
import com.sundry.user.User;
import com.sundry.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时检查配置里的超级管理员手机号。账号已存在就提升，未注册则等注册时自动赋角色。
 */
@Component
public class SuperAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminInitializer.class);

    private final SecurityProperties properties;
    private final UserMapper userMapper;

    public SuperAdminInitializer(SecurityProperties properties, UserMapper userMapper) {
        this.properties = properties;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        String phone = properties.getSuperAdminPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("app.security.super-admin-phone 未配置，注册账号不会自动成为超级管理员");
            return;
        }
        User user = userMapper.findByPhone(phone);
        if (user == null) {
            log.info("超级管理员手机号尚未注册: {}", mask(phone));
            return;
        }
        if (user.getRole() != Role.SUPER_ADMIN) {
            int updated = userMapper.updateRole(user.getId(), Role.SUPER_ADMIN);
            log.info("已将 {} 提升为超级管理员，updated={}", mask(phone), updated);
        }
    }

    private String mask(String phone) {
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
