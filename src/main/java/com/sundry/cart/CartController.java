package com.sundry.cart;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.cart.dto.AddCartRequest;
import com.sundry.cart.dto.UpdateCartRequest;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 当前用户的购物车。
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public List<CartItemView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.list(requireUser(user));
    }

    @PostMapping
    public CartItemView add(@Valid @RequestBody AddCartRequest request,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.add(requireUser(user), request);
    }

    @PutMapping("/{id}")
    public CartItemView update(@PathVariable Long id,
                               @Valid @RequestBody UpdateCartRequest request,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.update(requireUser(user), id, request.quantity());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id,
                       @AuthenticationPrincipal AuthenticatedUser user) {
        cartService.remove(requireUser(user), id);
    }

    private Long requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.id();
    }
}
