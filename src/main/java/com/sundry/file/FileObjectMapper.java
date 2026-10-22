package com.sundry.file;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 上传文件记录。
 */
@Mapper
public interface FileObjectMapper {

    int insert(FileObject fileObject);

    FileObject findById(@Param("id") Long id);

    int deleteById(@Param("id") Long id);
}
