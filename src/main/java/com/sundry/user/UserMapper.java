package com.sundry.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    User findByPhone(@Param("phone") String phone);

    User findById(@Param("id") Long id);

    int insert(User user);
}
