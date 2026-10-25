package com.sundry.feed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductEmbeddingMapper {

    int upsertBatch(@Param("items") List<ProductEmbedding> items);

    List<ProductEmbedding> findByProductIds(@Param("ids") List<Long> ids);

    long count();
}
