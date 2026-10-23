package com.sundry.order;

import com.sundry.common.api.PageResponse;
import com.sundry.order.dto.OrderView;
import com.sundry.order.dto.UpdateTrackingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家订单履约：打包、发货。
 */
@RestController
@RequestMapping("/api/admin/orders")
@Validated
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public PageResponse<OrderView> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.adminPage(status, page, size);
    }

    @GetMapping("/{orderNo}")
    public OrderView detail(@PathVariable String orderNo) {
        return orderService.adminDetail(orderNo);
    }

    @PostMapping("/{orderNo}/pack")
    public OrderView pack(@PathVariable String orderNo) {
        return orderService.pack(orderNo);
    }

    @PostMapping("/{orderNo}/ship")
    public OrderView ship(@PathVariable String orderNo,
                          @Valid @RequestBody UpdateTrackingRequest request) {
        return orderService.ship(orderNo, request.trackingNo());
    }
}
