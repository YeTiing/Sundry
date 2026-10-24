package com.sundry.ai;

import com.sundry.ai.dto.RerankRequest;
import com.sundry.catalog.Category;
import com.sundry.catalog.CategoryMapper;
import com.sundry.catalog.dto.ProductView;
import com.sundry.feed.CategoryWeight;
import com.sundry.feed.FeedQueryMapper;
import com.sundry.feed.FeedService;
import com.sundry.feed.PriceRange;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把用户行为和规则候选整理成给模型看的最小上下文。
 * 只发分类名、价位、商品名，不发手机号、地址和用户身份。
 */
@Service
public class AiFeedProfileService {

    private static final int LOOKBACK_DAYS = 14;
    private static final int TOP_CATEGORIES = 5;
    private static final int RECENT_VIEW_LIMIT = 5;

    private final FeedQueryMapper feedQueryMapper;
    private final CategoryMapper categoryMapper;
    private final FeedService feedService;
    private final AiProperties properties;

    public AiFeedProfileService(FeedQueryMapper feedQueryMapper, CategoryMapper categoryMapper,
                                FeedService feedService, AiProperties properties) {
        this.feedQueryMapper = feedQueryMapper;
        this.categoryMapper = categoryMapper;
        this.feedService = feedService;
        this.properties = properties;
    }

    public CandidateBundle build(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(LOOKBACK_DAYS);
        Map<Long, Double> weights = new HashMap<>();
        for (CategoryWeight weight : feedQueryMapper.findViewCategoryWeights(userId, since)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue(), Double::sum);
        }
        for (CategoryWeight weight : feedQueryMapper.findPurchasedCategoryWeights(userId)) {
            weights.merge(weight.getCategoryId(), weight.getWeight().doubleValue() * 3, Double::sum);
        }

        List<RerankRequest.CategoryPreference> preferences = weights.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(TOP_CATEGORIES)
                .map(entry -> {
                    Category category = categoryMapper.findById(entry.getKey());
                    return category == null
                            ? null
                            : new RerankRequest.CategoryPreference(category.getName(), entry.getValue());
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        PriceRange range = feedQueryMapper.findPriceRange(userId, since);
        RerankRequest.PriceBand priceBand = new RerankRequest.PriceBand(
                range == null ? null : range.getMinPrice(),
                range == null ? null : range.getMaxPrice());
        List<String> recentViews = feedQueryMapper.findRecentViewedNames(
                userId, since, RECENT_VIEW_LIMIT);

        RerankRequest.Profile profile = new RerankRequest.Profile(
                preferences, priceBand, recentViews);

        List<Long> ranked = feedService.rankIds(userId);
        int limit = Math.max(1, properties.getFeedCandidateLimit());
        int end = Math.min(limit, ranked.size());

        List<RerankRequest.Candidate> candidates = new ArrayList<>();
        for (Long id : ranked.subList(0, end)) {
            ProductView view = feedService.viewOrNull(id);
            if (view == null) {
                continue;
            }
            candidates.add(new RerankRequest.Candidate(view.id(), view.name(),
                    view.categoryName() == null ? "" : view.categoryName(), view.price()));
        }

        List<Long> tail = end < ranked.size()
                ? new ArrayList<>(ranked.subList(end, ranked.size()))
                : List.of();

        return new CandidateBundle(profile, candidates, tail);
    }

    public record CandidateBundle(RerankRequest.Profile profile,
                                  List<RerankRequest.Candidate> candidates,
                                  List<Long> tail) {
    }
}
