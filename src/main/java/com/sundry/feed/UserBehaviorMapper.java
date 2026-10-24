package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserBehaviorMapper {

    int insert(UserBehavior behavior);

    int insertBatch(@Param("items") List<UserBehavior> items);

    int deleteOldViews(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
