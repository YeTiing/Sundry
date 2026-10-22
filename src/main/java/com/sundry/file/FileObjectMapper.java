package com.sundry.file;

import org.apache.ibatis.annotations.Mapper;

/**
 * 上传文件记录。
 */
@Mapper
public interface FileObjectMapper {

    int insert(FileObject fileObject);
}
