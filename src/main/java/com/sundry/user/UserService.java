package com.sundry.user;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户读写。@Service 把这个类注册成 Spring Bean，构造器参数由容器自动注入。
 */
@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public User findByPhone(String phone) {
        return userMapper.findByPhone(phone);
    }

    public User findById(Long id) {
        return userMapper.findById(id);
    }

    public User requireById(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public User create(String phone, String passwordHash, Role role) {
        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordHash);
        user.setStatus(1);
        user.setRole(role);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // phone 唯一索引冲突：并发注册时兜底，防止两个请求同时通过前面的存在性检查
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        return user;
    }

    public User updateProfile(Long id, String nickname, String bio) {
        String cleanNickname = normalize(nickname);
        String cleanBio = normalize(bio);
        userMapper.updateProfile(id, cleanNickname, cleanBio);
        return requireById(id);
    }

    public User updateAvatar(Long id, Long avatarFileId) {
        userMapper.updateAvatar(id, avatarFileId);
        return requireById(id);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Page<User> findPage(int page, int size) {
        int offset = (page - 1) * size;
        return new Page<>(userMapper.findPage(offset, size), userMapper.countAll());
    }

    /**
     * 修改角色；超级管理员受 SQL 前置条件保护。返回是否真的更新了。
     */
    public boolean changeRole(User target, Role role) {
        if (target.getRole() == Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.ROLE_CHANGE_FORBIDDEN);
        }
        return userMapper.updateRole(target.getId(), role) > 0;
    }

    public record Page<T>(List<T> items, long total) {
    }
}
