package com.sundry.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品读写。前台查询只返回 ON_SALE，管理端可以看全部状态。
 */
@Mapper
public interface ProductMapper {

    int insert(Product product);

    int update(Product product);

    int updateStatus(@Param("id") Long id, @Param("status") ProductStatus status);

    int softDelete(@Param("id") Long id);

    Product findById(@Param("id") Long id);

    Product findPublicById(@Param("id") Long id);

    List<Product> findPageAdmin(@Param("status") ProductStatus status,
                                @Param("offset") long offset,
                                @Param("size") int size);

    long countAdmin(@Param("status") ProductStatus status);

    List<Product> findPagePublic(@Param("categoryId") Long categoryId,
                                 @Param("offset") long offset,
                                 @Param("size") int size);

    long countPublic(@Param("categoryId") Long categoryId);

    long countByDigitalFileId(@Param("fileId") Long fileId);

    long countByFileId(@Param("fileId") Long fileId);
}
