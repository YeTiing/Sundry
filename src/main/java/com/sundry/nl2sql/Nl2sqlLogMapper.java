package com.sundry.nl2sql;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface Nl2sqlLogMapper {

    int insert(Nl2sqlLog log);
}
