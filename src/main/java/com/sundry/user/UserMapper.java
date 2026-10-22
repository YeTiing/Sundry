package com.sundry.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis 映射接口。@Mapper 让 MyBatis 生成实现类，方法与 UserMapper.xml 里的 id 一一对应。
 */
@Mapper
public interface UserMapper {

    // @Param 给 SQL 里的 #{phone} 指定参数名；多个参数时尤其必要
    User findByPhone(@Param("phone") String phone);

    User findById(@Param("id") Long id);

    int insert(User user);
}
