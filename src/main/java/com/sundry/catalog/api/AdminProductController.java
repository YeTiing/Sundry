package com.sundry.catalog.api;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.dto.ChangeProductStatusRequest;
import com.sundry.catalog.dto.ProductView;
import com.sundry.catalog.dto.SaveProductRequest;
import com.sundry.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品管理，管理员及超级管理员可用。列表必须分页。
 */
@RestController
@RequestMapping("/api/admin/products")
@Validated
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public PageResponse<ProductView> list(@RequestParam(required = false) ProductStatus status,
                                          @RequestParam(defaultValue = "1") @Min(1) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.findAdminPage(status, page, size);
    }

    @GetMapping("/{id}")
    public ProductView detail(@PathVariable Long id) {
        return productService.findAdmin(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody SaveProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable Long id, @Valid @RequestBody SaveProductRequest request) {
        return productService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public ProductView changeStatus(@PathVariable Long id,
                                    @Valid @RequestBody ChangeProductStatusRequest request) {
        return productService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }
}
