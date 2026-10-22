package com.sundry.catalog.api;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.dto.CategoryView;
import com.sundry.catalog.dto.ProductView;
import com.sundry.common.api.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商城公开接口：不需要登录，只返回已上架商品和启用分类。
 */
@RestController
@RequestMapping("/api")
@Validated
public class CatalogController {

    private final ProductService productService;

    public CatalogController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return productService.categories();
    }

    @GetMapping("/products")
    public PageResponse<ProductView> products(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return productService.findPublicPage(categoryId, page, size);
    }

    @GetMapping("/products/{id}")
    public ProductView product(@PathVariable Long id) {
        return productService.findPublic(id);
    }
}
