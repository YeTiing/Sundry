package com.sundry.seckill;

import com.sundry.address.UserAddress;
import com.sundry.address.UserAddressMapper;
import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.order.Order;
import com.sundry.order.OrderItem;
import com.sundry.order.OrderItemMapper;
import com.sundry.order.OrderMapper;
import com.sundry.order.OrderNoGenerator;
import com.sundry.order.OrderService;
import com.sundry.order.OrderSource;
import com.sundry.order.OrderStatus;
import com.sundry.order.dto.OrderView;
import com.sundry.seckill.dto.CreateSeckillOrderRequest;
import com.sundry.storage.ObjectStorage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀下单：Redis Lua 预占库存，数据库条件更新兜底，失败回补 Redis。
 * 一个用户在一个活动里只能有一单（数据库唯一索引 + Redis 购买标记）。
 */
@Service
public class SeckillOrderService {

    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final UserAddressMapper addressMapper;
    private final ObjectStorage storage;
    private final SeckillStockService stockService;
    private final OrderService orderService;
    private final OrderNoGenerator orderNoGenerator;
    private final TransactionTemplate transactionTemplate;

    public SeckillOrderService(SeckillActivityMapper activityMapper, OrderMapper orderMapper,
                               OrderItemMapper orderItemMapper, ProductMapper productMapper,
                               UserAddressMapper addressMapper, ObjectStorage storage,
                               SeckillStockService stockService, OrderService orderService,
                               OrderNoGenerator orderNoGenerator,
                               TransactionTemplate transactionTemplate) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.addressMapper = addressMapper;
        this.storage = storage;
        this.stockService = stockService;
        this.orderService = orderService;
        this.orderNoGenerator = orderNoGenerator;
        this.transactionTemplate = transactionTemplate;
    }

    public OrderView create(Long userId, CreateSeckillOrderRequest request) {
        SeckillActivity activity = activityMapper.findById(request.activityId());
        if (activity == null || !isRunning(activity)) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }
        Product product = productMapper.findById(activity.getProductId());
        if (product == null || product.getDeletedAt() != null
                || product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.SECKILL_PRODUCT_NOT_SALEABLE);
        }

        // 重试或并发重复进入时，直接返回已有订单
        Order existing = orderMapper.findSeckillOrder(userId, activity.getId());
        if (existing != null) {
            return orderService.userDetail(userId, existing.getOrderNo());
        }

        boolean physical = product.getType() == ProductType.PHYSICAL;
        UserAddress address = null;
        if (physical) {
            if (request.addressId() == null) {
                throw new BusinessException(ErrorCode.ADDRESS_REQUIRED);
            }
            address = addressMapper.findByIdAndUser(request.addressId(), userId);
            if (address == null) {
                throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
            }
        }

        if (!stockService.ensureWarm(activity)) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }
        int code = stockService.tryDeduct(activity.getId(), userId, activity.getPerUserLimit());
        if (code == SeckillStockService.LIMIT_REACHED) {
            throw new BusinessException(ErrorCode.SECKILL_LIMIT_REACHED);
        }
        if (code == SeckillStockService.SOLD_OUT) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code != SeckillStockService.OK) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }

        UserAddress finalAddress = address;
        try {
            return transactionTemplate.execute(status ->
                    doCreate(userId, activity, product, finalAddress));
        } catch (DuplicateKeyException ex) {
            Order duplicate = orderMapper.findSeckillOrder(userId, activity.getId());
            if (duplicate != null) {
                // 另一个并发请求已经建单，只把多扣的库存还回去，购买标记保留
                stockService.restoreStock(activity.getId(), 1);
                return orderService.userDetail(userId, duplicate.getOrderNo());
            }
            stockService.rollbackReservation(activity.getId(), userId, 1);
            throw ex;
        } catch (RuntimeException ex) {
            stockService.rollbackReservation(activity.getId(), userId, 1);
            throw ex;
        }
    }

    private OrderView doCreate(Long userId, SeckillActivity activity, Product product,
                               UserAddress address) {
        if (activityMapper.incrementSold(activity.getId()) == 0) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (product.getType() == ProductType.PHYSICAL
                && productMapper.deductStock(product.getId(), 1) == 0) {
            throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
        }

        Order order = new Order();
        order.setOrderNo(orderNoGenerator.next());
        order.setActivityId(activity.getId());
        order.setOrderSource(OrderSource.SECKILL);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(activity.getSeckillPrice());
        order.setHasPhysical(product.getType() == ProductType.PHYSICAL ? 1 : 0);
        if (address != null) {
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getReceiverPhone());
            order.setReceiverAddress(address.getProvince() + address.getCity()
                    + address.getDistrict() + address.getDetail());
        }
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setProductType(product.getType());
        item.setPrice(activity.getSeckillPrice());
        item.setQuantity(1);
        item.setCoverUrl(coverUrl(product));
        item.setDigitalFileId(product.getDigitalFileId());
        orderItemMapper.insertBatch(List.of(item));

        return orderService.userDetail(userId, order.getOrderNo());
    }

    private boolean isRunning(SeckillActivity activity) {
        LocalDateTime now = LocalDateTime.now();
        return activity.getStatus() == SeckillStatus.ACTIVE
                && !now.isBefore(activity.getStartAt())
                && now.isBefore(activity.getEndAt());
    }

    private String coverUrl(Product product) {
        if (product.getCoverBucket() == null || product.getCoverObjectKey() == null) {
            return null;
        }
        return storage.publicUrl(product.getCoverBucket(), product.getCoverObjectKey());
    }
}
