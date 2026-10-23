package com.sundry.file;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上传文件记录。
 */
@Mapper
public interface FileObjectMapper {

    int insert(FileObject fileObject);

    FileObject findById(@Param("id") Long id);

    int softDelete(@Param("id") Long id);

    int restore(@Param("id") Long id);

    FileObject findReusable(@Param("uploaderId") Long uploaderId,
                            @Param("kind") FileKind kind,
                            @Param("sizeBytes") long sizeBytes,
                            @Param("etag") String etag);

    List<FileObject> findCleanupCandidates(@Param("kind") FileKind kind,
                                           @Param("cutoff") LocalDateTime cutoff,
                                           @Param("limit") int limit);

    int deleteById(@Param("id") Long id);
}
