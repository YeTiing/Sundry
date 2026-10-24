package com.sundry.ai;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiFeedLogMapper {

    int insert(AiFeedLog log);
}
