package com.sundry.assistant;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssistantChatLogMapper {

    int insert(AssistantChatLog log);
}
