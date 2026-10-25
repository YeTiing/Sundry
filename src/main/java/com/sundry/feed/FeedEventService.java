package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.feed.dto.FeedEventRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 曝光/点击上报。分类由服务端按商品补全，不信任前端传值；
 * user_id 由 Controller 从 JWT 注入。
 */
@Service
public class FeedEventService {

    private final ProductMapper productMapper;
    private final FeedEventMapper feedEventMapper;

    public FeedEventService(ProductMapper productMapper, FeedEventMapper feedEventMapper) {
        this.productMapper = productMapper;
        this.feedEventMapper = feedEventMapper;
    }

    public int record(Long userId, FeedEventRequest request) {
        List<Long> productIds = request.events().stream()
                .map(FeedEventRequest.EventItem::productId)
                .distinct()
                .toList();

        Map<Long, Long> categoryById = new HashMap<>();
        for (Product product : productMapper.findCategoriesByIds(productIds)) {
            categoryById.put(product.getId(), product.getCategoryId());
        }

        String buildId = blankToNull(request.buildId());
        Set<String> seen = new HashSet<>();
        List<FeedEvent> rows = new ArrayList<>();

        for (FeedEventRequest.EventItem item : request.events()) {
            Long categoryId = categoryById.get(item.productId());
            if (categoryId == null) {
                continue;
            }
            String key = item.type() + ":" + item.productId() + ":" + item.position();
            if (!seen.add(key)) {
                continue;
            }
            FeedEvent event = new FeedEvent();
            event.setTraceId(request.traceId());
            event.setUserId(userId);
            event.setEventType(item.type());
            event.setAlgorithm(request.algorithm());
            event.setBuildId(buildId);
            event.setProductId(item.productId());
            event.setCategoryId(categoryId);
            event.setItemPosition(item.position());
            rows.add(event);
        }

        if (rows.isEmpty()) {
            return 0;
        }
        return feedEventMapper.insertBatch(rows);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
