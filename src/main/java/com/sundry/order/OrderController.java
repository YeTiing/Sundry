package com.sundry.order;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.order.dto.CreateOrderRequest;
import com.sundry.order.dto.DownloadView;
import com.sundry.order.dto.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户订单接口。
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String requestId,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.create(requireUser(user), request, requestId);
    }

    @GetMapping
    public PageResponse<OrderView> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.userPage(requireUser(user), page, size);
    }

    @GetMapping("/{orderNo}")
    public OrderView detail(@PathVariable String orderNo,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.userDetail(requireUser(user), orderNo);
    }

    @PostMapping("/{orderNo}/pay")
    public OrderView pay(@PathVariable String orderNo,
                         @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.pay(requireUser(user), orderNo);
    }

    @PostMapping("/{orderNo}/cancel")
    public OrderView cancel(@PathVariable String orderNo,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.cancel(requireUser(user), orderNo);
    }

    @PostMapping("/{orderNo}/confirm")
    public OrderView confirm(@PathVariable String orderNo,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.confirm(requireUser(user), orderNo);
    }

    @GetMapping("/{orderNo}/items/{itemId}/download")
    public DownloadView download(@PathVariable String orderNo,
                                 @PathVariable Long itemId,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.download(requireUser(user), orderNo, itemId);
    }

    private Long requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.id();
    }
}
