package com.sundry.nl2sql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface Nl2sqlLogMapper {

    int insert(Nl2sqlLog log);

    List<Nl2sqlLog> findPage(@Param("status") String status,
                             @Param("offset") long offset,
                             @Param("size") int size);

    long count(@Param("status") String status);
}
