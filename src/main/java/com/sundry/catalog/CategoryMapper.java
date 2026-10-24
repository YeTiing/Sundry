package com.sundry.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分类读写。公开接口只读启用分类，管理端可以维护全部。
 */
@Mapper
public interface CategoryMapper {

    Category findById(@Param("id") Long id);

    Category findByName(@Param("name") String name);

    List<Category> findActive();

    List<Category> findAll();

    int insert(Category category);

    int update(Category category);

    int delete(@Param("id") Long id);

    long countProducts(@Param("categoryId") Long categoryId);
}
