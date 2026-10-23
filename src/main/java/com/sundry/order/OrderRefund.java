package com.sundry.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款申请流水：记录用户申请、审核结果和审核人，被拒后重新申请会产生新行。
 */
public class OrderRefund {

    private Long id;
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private String reason;
    private RefundStatus status;
    private OrderStatus prevStatus;
    private LocalDateTime appliedAt;
    private LocalDateTime reviewedAt;
    private Long reviewerId;
    private String reviewRemark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public void setStatus(RefundStatus status) {
        this.status = status;
    }

    public OrderStatus getPrevStatus() {
        return prevStatus;
    }

    public void setPrevStatus(OrderStatus prevStatus) {
        this.prevStatus = prevStatus;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewRemark() {
        return reviewRemark;
    }

    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
    }
}
