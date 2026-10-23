package com.sundry.cart;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.cart.dto.AddCartRequest;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.storage.ObjectStorage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 购物车只记录商品和数量，不锁库存；真正扣库存发生在下单。
 */
@Service
public class CartService {

    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;
    private final ObjectStorage storage;

    public CartService(CartItemMapper cartItemMapper, ProductMapper productMapper,
                       ObjectStorage storage) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.storage = storage;
    }

    public List<CartItemView> list(Long userId) {
        return cartItemMapper.findByUser(userId).stream().map(this::toView).toList();
    }

    public CartItemView add(Long userId, AddCartRequest request) {
        Product product = requireOnSale(request.productId());
        CartItem existing = cartItemMapper.findByUserAndProduct(userId, request.productId());
        int newQuantity = request.quantity() + (existing == null ? 0 : existing.getQuantity());
        ensureStock(product, newQuantity);

        if (existing == null) {
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setProductId(request.productId());
            item.setQuantity(request.quantity());
            try {
                cartItemMapper.insert(item);
            } catch (DuplicateKeyException ex) {
                // 并发加入同一商品：转成更新数量
                existing = cartItemMapper.findByUserAndProduct(userId, request.productId());
                if (existing == null) {
                    throw ex;
                }
                newQuantity = request.quantity() + existing.getQuantity();
                ensureStock(product, newQuantity);
                cartItemMapper.updateQuantity(existing.getId(), userId, newQuantity);
                return toView(cartItemMapper.findByIdAndUser(existing.getId(), userId));
            }
            return toView(cartItemMapper.findByIdAndUser(item.getId(), userId));
        }

        cartItemMapper.updateQuantity(existing.getId(), userId, newQuantity);
        return toView(cartItemMapper.findByIdAndUser(existing.getId(), userId));
    }

    public CartItemView update(Long userId, Long itemId, int quantity) {
        CartItem item = cartItemMapper.findByIdAndUser(itemId, userId);
        if (item == null) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        Product product = requireOnSale(item.getProductId());
        ensureStock(product, quantity);
        cartItemMapper.updateQuantity(itemId, userId, quantity);
        return toView(cartItemMapper.findByIdAndUser(itemId, userId));
    }

    public void remove(Long userId, Long itemId) {
        if (cartItemMapper.deleteByIdAndUser(itemId, userId) == 0) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private Product requireOnSale(Long productId) {
        Product product = productMapper.findById(productId);
        if (product == null || product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    private void ensureStock(Product product, int quantity) {
        if (product.getType() == ProductType.PHYSICAL
                && (product.getStock() == null || product.getStock() < quantity)) {
            throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
        }
    }

    private CartItemView toView(CartItem item) {
        String coverUrl = null;
        if (item.getCoverBucket() != null && item.getCoverObjectKey() != null) {
            coverUrl = storage.publicUrl(item.getCoverBucket(), item.getCoverObjectKey());
        }
        boolean available = item.getProductStatus() == ProductStatus.ON_SALE
                && item.getProductDeletedAt() == null;
        return new CartItemView(item.getId(), item.getProductId(), item.getProductName(),
                item.getProductType(), item.getPrice(), item.getQuantity(),
                item.getStock() == null ? 0 : item.getStock(), coverUrl, available);
    }
}
