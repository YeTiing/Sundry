package com.sundry.shop;

import com.sundry.shop.dto.UpdateShopRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺资料管理，仅超级管理员（商家本人）。
 */
@RestController
@RequestMapping("/api/admin/shop")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminShopController {

    private final ShopService shopService;

    public AdminShopController(ShopService shopService) {
        this.shopService = shopService;
    }

    @GetMapping
    public ShopView get() {
        return shopService.view();
    }

    @PutMapping
    public ShopView update(@Valid @RequestBody UpdateShopRequest request) {
        return shopService.update(request);
    }
}
