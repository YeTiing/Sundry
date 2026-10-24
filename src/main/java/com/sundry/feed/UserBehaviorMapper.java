package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserBehaviorMapper {

    int insert(UserBehavior behavior);
}
