package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FeedEventMapper {

    int insertBatch(@Param("items") List<FeedEvent> items);
}
