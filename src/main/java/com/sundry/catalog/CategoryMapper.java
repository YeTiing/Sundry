package com.sundry.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分类查询。分类数据由迁移种子提供。
 */
@Mapper
public interface CategoryMapper {

    Category findById(@Param("id") Long id);

    List<Category> findActive();
}
