package com.sundry.catalog.api;

import com.sundry.catalog.CategoryService;
import com.sundry.catalog.dto.AdminCategoryView;
import com.sundry.catalog.dto.SaveCategoryRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台分类管理。停用分类不会删除商品。
 */
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<AdminCategoryView> list() {
        return categoryService.adminList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCategoryView create(@Valid @RequestBody SaveCategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    public AdminCategoryView update(@PathVariable Long id,
                                    @Valid @RequestBody SaveCategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }
}
