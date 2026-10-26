package com.sundry.feed;

import com.sundry.catalog.ProductService;
import com.sundry.catalog.ProductType;
import com.sundry.catalog.dto.ProductView;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EXPLORE 变体的硬探索：把低曝光、非偏好类目的商品强制换进固定位置。
 * 不再依赖模型是否“愿意”探索。
 */
@Service
public class FeedExploreService {

    private static final int MIN_SIZE = 3;
    private static final int HEAD_SIZE = 5;

    private final ProductService productService;
    private final FeedQueryMapper queryMapper;
    private final FeedProperties properties;

    public FeedExploreService(ProductService productService, FeedQueryMapper queryMapper,
                              FeedProperties properties) {
        this.productService = productService;
        this.queryMapper = queryMapper;
        this.properties = properties;
    }

    public ExploreResult apply(Long userId, List<Long> rankedIds, List<Long> poolIds) {
        int size = rankedIds == null ? 0 : rankedIds.size();
        if (!properties.isExploreEnabled() || size < MIN_SIZE) {
            return unchanged(rankedIds);
        }
        int slots = Math.max(1, (int) Math.round(
                size * properties.getExploreSlotsPercent() / 100.0));
        Set<Long> rankedSet = new HashSet<>(rankedIds);
        List<Long> pool = (poolIds == null ? List.<Long>of() : poolIds).stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(id -> !rankedSet.contains(id))
                .limit(properties.getExplorePoolLimit())
                .toList();
        if (pool.isEmpty()) {
            return unchanged(rankedIds);
        }

        Map<Long, ProductView> views = productService.findPublicBatch(pool);
        Map<Long, ProductView> headViews = productService.findPublicBatch(
                rankedIds.subList(0, Math.min(HEAD_SIZE, size)));
        Set<Long> headCategories = new HashSet<>();
        for (ProductView view : headViews.values()) {
            if (view.categoryId() != null) {
                headCategories.add(view.categoryId());
            }
        }

        LocalDateTime since = LocalDateTime.now().minusDays(properties.getExploreExposureDays());
        Map<Long, Long> productExposures = new HashMap<>();
        for (FeedQueryMapper.ProductExposureCount row
                : queryMapper.countProductExposures(pool, since)) {
            productExposures.put(row.getProductId(),
                    row.getExposures() == null ? 0 : row.getExposures());
        }
        Map<Long, Long> categoryExposures = new HashMap<>();
        for (FeedQueryMapper.CategoryExposureCount row
                : queryMapper.countCategoryExposures(since)) {
            categoryExposures.put(row.getCategoryId(),
                    row.getExposures() == null ? 0 : row.getExposures());
        }

        List<ExploreCandidate> cold = new ArrayList<>();
        for (Long id : pool) {
            ProductView view = views.get(id);
            if (view == null) {
                continue;
            }
            if (view.type() == ProductType.PHYSICAL
                    && (view.stock() == null || view.stock() <= 0)) {
                continue;
            }
            long exposure = productExposures.getOrDefault(id, 0L);
            if (exposure > properties.getExploreExposureThreshold()) {
                continue;
            }
            long categoryExposure = view.categoryId() == null
                    ? 0 : categoryExposures.getOrDefault(view.categoryId(), 0L);
            cold.add(new ExploreCandidate(id, view.categoryId(), exposure,
                    categoryExposure, stable(userId, id)));
        }
        if (cold.isEmpty()) {
            return unchanged(rankedIds);
        }
        List<ExploreCandidate> nonHead = cold.stream()
                .filter(candidate -> !headCategories.contains(candidate.categoryId()))
                .toList();
        List<ExploreCandidate> source = nonHead.size() >= slots ? nonHead : cold;
        source = source.stream()
                .sorted(Comparator.comparingLong(ExploreCandidate::exposure)
                        .thenComparingLong(ExploreCandidate::categoryExposure)
                        .thenComparingInt(ExploreCandidate::order)
                        .thenComparingLong(ExploreCandidate::id))
                .toList();

        List<Long> result = new ArrayList<>(rankedIds);
        Set<Long> explored = new LinkedHashSet<>();
        Set<Integer> usedPositions = new HashSet<>();
        int step = Math.max(2, size / (Math.min(slots, source.size()) + 1));
        for (int i = 0; i < slots && i < source.size(); i++) {
            int position = 1 + i * step;
            while (position < size && usedPositions.contains(position)) {
                position++;
            }
            if (position >= size) {
                position = size - 1;
                while (position > 0 && usedPositions.contains(position)) {
                    position--;
                }
            }
            if (position <= 0) {
                break;
            }
            usedPositions.add(position);
            result.set(position, source.get(i).id());
            explored.add(source.get(i).id());
        }
        return explored.isEmpty() ? unchanged(rankedIds)
                : new ExploreResult(List.copyOf(result), Set.copyOf(explored));
    }

    private ExploreResult unchanged(List<Long> rankedIds) {
        return new ExploreResult(rankedIds == null ? List.of() : List.copyOf(rankedIds), Set.of());
    }

    private int stable(Long userId, Long productId) {
        return Math.floorMod(Objects.hash(userId, productId, LocalDate.now().toEpochDay()), 10_000);
    }

    private record ExploreCandidate(Long id, Long categoryId, long exposure,
                                    long categoryExposure, int order) {
    }

    public record ExploreResult(List<Long> ids, Set<Long> exploredIds) {

        public boolean changed() {
            return !exploredIds.isEmpty();
        }
    }
}
