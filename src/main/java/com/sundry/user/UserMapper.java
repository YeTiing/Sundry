package com.sundry.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis 映射接口。@Mapper 让 MyBatis 生成实现类，方法与 UserMapper.xml 里的 id 一一对应。
 */
@Mapper
public interface UserMapper {

    // @Param 给 SQL 里的 #{phone} 指定参数名；多个参数时尤其必要
    User findByPhone(@Param("phone") String phone);

    User findById(@Param("id") Long id);

    // 订单列表批量取买家，避免逐单查询
    List<User> findByIds(@Param("ids") List<Long> ids);

    int insert(User user);

    List<User> findPage(@Param("offset") int offset, @Param("size") int size);

    long countAll();

    // 带前置条件：超级管理员不能被这个 SQL 改掉
    int updateRole(@Param("id") Long id, @Param("role") Role role);

    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("bio") String bio);

    int updateAvatar(@Param("id") Long id, @Param("avatarFileId") Long avatarFileId);
}
