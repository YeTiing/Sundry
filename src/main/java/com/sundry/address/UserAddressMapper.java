package com.sundry.address;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserAddressMapper {

    List<UserAddress> findByUser(@Param("userId") Long userId);

    UserAddress findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    long countByUser(@Param("userId") Long userId);

    int insert(UserAddress address);

    int update(UserAddress address);

    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    int clearDefault(@Param("userId") Long userId);

    int setDefault(@Param("id") Long id, @Param("userId") Long userId);
}
