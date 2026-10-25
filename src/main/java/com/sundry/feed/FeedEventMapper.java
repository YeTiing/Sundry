package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedEventMapper {

    int insertBatch(@Param("items") List<FeedEvent> items);

    int deleteOld(@Param("eventType") String eventType,
                  @Param("cutoff") LocalDateTime cutoff,
                  @Param("limit") int limit);
}
