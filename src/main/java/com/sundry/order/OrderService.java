package com.sundry.order;

import com.sundry.address.UserAddress;
import com.sundry.address.UserAddressMapper;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.cart.CartItem;
import com.sundry.cart.CartItemMapper;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.file.digital.DigitalUploadGateway;
import com.sundry.order.dto.CreateOrderRequest;
import com.sundry.order.dto.DownloadView;
import com.sundry.order.dto.OrderItemView;
import com.sundry.order.dto.OrderView;
import com.sundry.storage.ObjectStorage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一套订单流程：
 * 事务 + 条件扣库存 + (user_id, request_id) 唯一索引；不依赖 Redis。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;
    private final UserAddressMapper addressMapper;
    private final ObjectStorage storage;
    private final FileObjectMapper fileObjectMapper;
    private final DigitalUploadGateway digitalGateway;
    private final TransactionTemplate transactionTemplate;
    private final OrderProperties properties;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                        CartItemMapper cartItemMapper, ProductMapper productMapper,
                        UserAddressMapper addressMapper, ObjectStorage storage,
                        FileObjectMapper fileObjectMapper, DigitalUploadGateway digitalGateway,
                        TransactionTemplate transactionTemplate, OrderProperties properties) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.addressMapper = addressMapper;
        this.storage = storage;
        this.fileObjectMapper = fileObjectMapper;
        this.digitalGateway = digitalGateway;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    public OrderView create(Long userId, CreateOrderRequest request, String rawRequestId) {
        String requestId = normalize(rawRequestId);
        if (requestId != null) {
            Order existing = orderMapper.findByUserAndRequestId(userId, requestId);
            if (existing != null) {
                return toView(existing);
            }
        }
        try {
            return transactionTemplate.execute(status -> doCreate(userId, request, requestId));
        } catch (DuplicateKeyException ex) {
            if (requestId != null) {
                Order existing = orderMapper.findByUserAndRequestId(userId, requestId);
                if (existing != null) {
                    return toView(existing);
                }
            }
            throw ex;
        }
    }

    private OrderView doCreate(Long userId, CreateOrderRequest request, String requestId) {
        Set<Long> selectedIds = new HashSet<>(request.cartItemIds());
        List<CartItem> selected = cartItemMapper.findByUser(userId).stream()
                .filter(item -> selectedIds.contains(item.getId()))
                .toList();
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }
        if (selected.size() != selectedIds.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }

        for (CartItem item : selected) {
            if (item.getProductStatus() != ProductStatus.ON_SALE || item.getProductDeletedAt() != null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
        }

        boolean hasPhysical = selected.stream()
                .anyMatch(item -> item.getProductType() == ProductType.PHYSICAL);

        UserAddress address = null;
        if (hasPhysical) {
            if (request.addressId() == null) {
                throw new BusinessException(ErrorCode.ADDRESS_REQUIRED);
            }
            address = addressMapper.findByIdAndUser(request.addressId(), userId);
            if (address == null) {
                throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
            }
        }

        // 条件扣库存；任何一件失败都抛异常，事务整体回滚
        for (CartItem item : selected) {
            if (item.getProductType() == ProductType.PHYSICAL
                    && productMapper.deductStock(item.getProductId(), item.getQuantity()) == 0) {
                throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
            }
        }

        BigDecimal total = selected.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        Order order = new Order();
        order.setOrderNo(newOrderNo());
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(total);
        order.setHasPhysical(hasPhysical ? 1 : 0);
        if (address != null) {
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getReceiverPhone());
            order.setReceiverAddress(address.getProvince() + address.getCity()
                    + address.getDistrict() + address.getDetail());
        }
        orderMapper.insert(order);

        orderItemMapper.insertBatch(selected.stream().map(item -> {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(item.getProductId());
            orderItem.setProductName(item.getProductName());
            orderItem.setProductType(item.getProductType());
            orderItem.setPrice(item.getPrice());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setCoverUrl(coverUrl(item));
            orderItem.setDigitalFileId(item.getDigitalFileId());
            return orderItem;
        }).toList());

        for (CartItem item : selected) {
            cartItemMapper.deleteByIdAndUser(item.getId(), userId);
        }

        return toView(orderMapper.findByOrderNoAndUser(order.getOrderNo(), userId));
    }

    public OrderView pay(Long userId, String orderNo) {
        Order order = requireUserOrder(userId, orderNo);
        if (isPaidLike(order.getStatus())) {
            return toView(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        if (orderMapper.markPaid(orderNo, userId) == 0) {
            Order refreshed = requireUserOrder(userId, orderNo);
            if (isPaidLike(refreshed.getStatus())) {
                return toView(refreshed);
            }
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        return toView(requireUserOrder(userId, orderNo));
    }

    public OrderView cancel(Long userId, String orderNo) {
        Order order = requireUserOrder(userId, orderNo);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toView(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        Integer changed = transactionTemplate.execute(status -> {
            if (orderMapper.cancel(orderNo, userId) == 0) {
                return 0;
            }
            restoreStock(order);
            return 1;
        });
        Order refreshed = requireUserOrder(userId, orderNo);
        if (changed == null || changed == 0) {
            if (refreshed.getStatus() == OrderStatus.CANCELLED) {
                return toView(refreshed);
            }
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        return toView(refreshed);
    }

    public OrderView confirm(Long userId, String orderNo) {
        Order order = requireUserOrder(userId, orderNo);
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return toView(order);
        }
        if (orderMapper.confirm(orderNo, userId) == 0) {
            Order refreshed = requireUserOrder(userId, orderNo);
            if (refreshed.getStatus() == OrderStatus.COMPLETED) {
                return toView(refreshed);
            }
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        return toView(requireUserOrder(userId, orderNo));
    }

    public OrderView pack(String orderNo) {
        Order order = requireOrder(orderNo);
        if (order.getStatus() == OrderStatus.PACKING) {
            return toView(order);
        }
        if (orderMapper.pack(orderNo) == 0) {
            Order refreshed = requireOrder(orderNo);
            if (refreshed.getStatus() == OrderStatus.PACKING) {
                return toView(refreshed);
            }
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        return toView(requireOrder(orderNo));
    }

    public OrderView ship(String orderNo, String trackingNo) {
        Order order = requireOrder(orderNo);
        if (order.getStatus() == OrderStatus.SHIPPED) {
            return toView(order);
        }
        if (orderMapper.ship(orderNo, trackingNo.trim()) == 0) {
            Order refreshed = requireOrder(orderNo);
            if (refreshed.getStatus() == OrderStatus.SHIPPED) {
                return toView(refreshed);
            }
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        return toView(requireOrder(orderNo));
    }

    public PageResponse<OrderView> userPage(Long userId, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<OrderView> items = orderMapper.findPageByUser(userId, offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, orderMapper.countByUser(userId), page, size);
    }

    public OrderView userDetail(Long userId, String orderNo) {
        return toView(requireUserOrder(userId, orderNo));
    }

    public PageResponse<OrderView> adminPage(OrderStatus status, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<OrderView> items = orderMapper.findPageAdmin(status, offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, orderMapper.countAdmin(status), page, size);
    }

    public OrderView adminDetail(String orderNo) {
        return toView(requireOrder(orderNo));
    }

    public DownloadView download(Long userId, String orderNo, Long itemId) {
        Order order = requireUserOrder(userId, orderNo);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT
                || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID);
        }
        OrderItem item = orderItemMapper.findByIdAndOrder(itemId, order.getId());
        if (item == null || item.getProductType() != ProductType.DIGITAL
                || item.getDigitalFileId() == null) {
            throw new BusinessException(ErrorCode.DIGITAL_ITEM_NOT_FOUND);
        }
        FileObject file = fileObjectMapper.findById(item.getDigitalFileId());
        if (file == null || file.getKind() != FileKind.DIGITAL) {
            throw new BusinessException(ErrorCode.DIGITAL_ITEM_NOT_FOUND);
        }
        String url = digitalGateway.presignGet(file.getObjectKey(), DOWNLOAD_TTL);
        return new DownloadView(url, DOWNLOAD_TTL.toSeconds());
    }

    /**
     * 未支付超时取消，回补库存；条件更新保证并发下只回补一次。
     */
    public void cancelTimeoutOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getPayTimeout());
        List<Order> candidates = orderMapper.findTimeoutCandidates(cutoff, properties.getCancelBatchSize());
        for (Order order : candidates) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (orderMapper.cancelById(order.getId()) == 1) {
                        restoreStock(order);
                    }
                });
            } catch (RuntimeException ignored) {
                // 单个订单失败不影响其他订单，下一轮还会重试
            }
        }
    }

    private void restoreStock(Order order) {
        for (OrderItem item : orderItemMapper.findByOrderId(order.getId())) {
            if (item.getProductType() == ProductType.PHYSICAL) {
                productMapper.restoreStock(item.getProductId(), item.getQuantity());
            }
        }
    }

    private Order requireUserOrder(Long userId, String orderNo) {
        Order order = orderMapper.findByOrderNoAndUser(orderNo, userId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private Order requireOrder(String orderNo) {
        Order order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private boolean isPaidLike(OrderStatus status) {
        return status == OrderStatus.PAID || status == OrderStatus.PACKING
                || status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED;
    }

    private String coverUrl(CartItem item) {
        if (item.getCoverBucket() == null || item.getCoverObjectKey() == null) {
            return null;
        }
        return storage.publicUrl(item.getCoverBucket(), item.getCoverObjectKey());
    }

    private String newOrderNo() {
        return LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String normalize(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String trimmed = requestId.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private OrderView toView(Order order) {
        List<OrderItemView> items = orderItemMapper.findByOrderId(order.getId()).stream()
                .map(item -> new OrderItemView(item.getId(), item.getProductId(), item.getProductName(),
                        item.getProductType(), item.getPrice(), item.getQuantity(),
                        item.getCoverUrl(), item.getDigitalFileId()))
                .toList();
        return new OrderView(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                order.getHasPhysical() != null && order.getHasPhysical() == 1,
                order.getReceiverName(), order.getReceiverPhone(), order.getReceiverAddress(),
                order.getTrackingNo(), order.getCreatedAt(), order.getPaidAt(), order.getPackedAt(),
                order.getShippedAt(), order.getCompletedAt(), order.getCancelledAt(), items);
    }
}
