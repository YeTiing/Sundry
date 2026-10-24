package com.sundry.ai.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Java -> Python 的重排请求。字段名与 Python 端 camelCase 别名一致。
 */
public record RerankRequest(Long userId, Profile profile, List<Candidate> candidates, int limit) {

    public record Profile(List<CategoryPreference> categories, PriceBand priceBand,
                          List<String> recentViews) {
    }

    public record CategoryPreference(String name, double weight) {
    }

    public record PriceBand(BigDecimal min, BigDecimal max) {
    }

    public record Candidate(Long id, String name, String category, BigDecimal price) {
    }
}
