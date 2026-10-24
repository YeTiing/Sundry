package com.sundry.catalog;

import com.sundry.catalog.dto.AdminCategoryView;
import com.sundry.catalog.dto.SaveCategoryRequest;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类管理。停用只影响前台展示，不迁移商品。
 */
@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final ProductCacheService cache;

    public CategoryService(CategoryMapper categoryMapper, ProductCacheService cache) {
        this.categoryMapper = categoryMapper;
        this.cache = cache;
    }

    public List<AdminCategoryView> adminList() {
        return categoryMapper.findAll().stream()
                .map(category -> AdminCategoryView.from(category,
                        categoryMapper.countProducts(category.getId())))
                .toList();
    }

    public AdminCategoryView create(SaveCategoryRequest request) {
        String name = request.name().trim();
        if (categoryMapper.findByName(name) != null) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        Category category = new Category();
        category.setName(name);
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(request.status() == null ? 1 : request.status());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }
        cache.evictCategories();
        return AdminCategoryView.from(categoryMapper.findById(category.getId()), 0);
    }

    public AdminCategoryView update(Long id, SaveCategoryRequest request) {
        Category category = categoryMapper.findById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        String name = request.name().trim();
        Category sameName = categoryMapper.findByName(name);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }

        category.setName(name);
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            category.setStatus(request.status());
        }
        try {
            categoryMapper.update(category);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }
        cache.evictCategories();
        return AdminCategoryView.from(categoryMapper.findById(id),
                categoryMapper.countProducts(id));
    }

    public void delete(Long id) {
        if (categoryMapper.findById(id) == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        if (categoryMapper.countProducts(id) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_IN_USE);
        }
        categoryMapper.delete(id);
        cache.evictCategories();
    }
}
