package com.sundry.feed;

/**
 * feed_event 表行对象。user_id 永远来自 JWT，不来自请求体。
 */
public class FeedEvent {

    private Long id;
    private String traceId;
    private Long userId;
    private FeedEventType eventType;
    private FeedAlgorithm algorithm;
    private String buildId;
    private Long productId;
    private Long categoryId;
    private Integer itemPosition;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public FeedEventType getEventType() {
        return eventType;
    }

    public void setEventType(FeedEventType eventType) {
        this.eventType = eventType;
    }

    public FeedAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(FeedAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getItemPosition() {
        return itemPosition;
    }

    public void setItemPosition(Integer itemPosition) {
        this.itemPosition = itemPosition;
    }
}
