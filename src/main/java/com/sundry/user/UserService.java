package com.sundry.user;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

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

    public User create(String phone, String passwordHash) {
        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordHash);
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        return user;
    }
}
