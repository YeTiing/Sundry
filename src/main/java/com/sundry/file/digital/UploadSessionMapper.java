package com.sundry.file.digital;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface UploadSessionMapper {

    int insert(UploadSession session);

    UploadSession findById(@Param("uploadId") String uploadId);

    /**
     * 幂等完成：已存在的会话直接更新 file_id/status。
     */
    int complete(@Param("uploadId") String uploadId,
                 @Param("uploaderId") Long uploaderId,
                 @Param("objectKey") String objectKey,
                 @Param("fileId") Long fileId);

    int deletePendingBefore(@Param("cutoff") LocalDateTime cutoff,
                            @Param("limit") int limit);
}
