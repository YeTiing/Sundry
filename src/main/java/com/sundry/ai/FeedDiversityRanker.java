package com.sundry.ai;

import com.sundry.ai.FeedSnapshot.SnapshotItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 排序后的保序纠偏：
 * 1. 同一分类最多连续 maxConsecutive 个；
 * 2. TopK 至少出现 minCategoriesTop 个不同分类。
 * 只对 PREF 变体使用；EXPLORE 由 FeedExploreService 处理。
 */
public final class FeedDiversityRanker {

    private static final String UNKNOWN = "__unknown__";

    private FeedDiversityRanker() {
    }

    public record Result(List<SnapshotItem> items, int moved, int relaxed) {
        public boolean changed() {
            return moved > 0 || relaxed > 0;
        }
    }

    public static Result reorder(List<SnapshotItem> input,
                                 Map<Long, String> categoryById,
                                 int maxConsecutive,
                                 int minCategoriesTop,
                                 int topK) {
        if (input == null || input.isEmpty()) {
            return new Result(List.of(), 0, 0);
        }
        if (maxConsecutive <= 0 && minCategoriesTop <= 0) {
            return new Result(new ArrayList<>(input), 0, 0);
        }

        List<SnapshotItem> remaining = new ArrayList<>(input);
        List<SnapshotItem> output = new ArrayList<>(input.size());
        int moved = 0;
        int relaxed = 0;

        String lastCategory = null;
        int streak = 0;
        while (!remaining.isEmpty()) {
            int pick = 0;
            if (maxConsecutive > 0) {
                pick = -1;
                for (int i = 0; i < remaining.size(); i++) {
                    String category = categoryOf(remaining.get(i), categoryById);
                    if (!category.equals(lastCategory) || streak < maxConsecutive) {
                        pick = i;
                        break;
                    }
                }
                if (pick < 0) {
                    // 剩余商品全是同一分类，无法满足约束，保底放行
                    pick = 0;
                    relaxed++;
                }
            }
            if (pick > 0) {
                moved++;
            }
            SnapshotItem item = remaining.remove(pick);
            String category = categoryOf(item, categoryById);
            if (category.equals(lastCategory)) {
                streak++;
            } else {
                lastCategory = category;
                streak = 1;
            }
            output.add(item);
        }

        if (minCategoriesTop > 0 && topK > 0) {
            int k = Math.min(topK, output.size());
            while (distinctKnown(output, k, categoryById) < minCategoriesTop) {
                Set<String> topCategories = knownCategories(output, k, categoryById);
                int source = -1;
                for (int i = k; i < output.size(); i++) {
                    String category = categoryById.get(output.get(i).id());
                    if (category != null && !topCategories.contains(category)) {
                        source = i;
                        break;
                    }
                }
                if (source < 0) {
                    relaxed++;
                    break;
                }
                int target = swapTarget(output, k, categoryById);
                if (target < 0) {
                    relaxed++;
                    break;
                }
                Collections.swap(output, target, source);
                moved++;
            }
        }

        return new Result(output, moved, relaxed);
    }

    private static String categoryOf(SnapshotItem item, Map<Long, String> categoryById) {
        return categoryById.getOrDefault(item.id(), UNKNOWN);
    }

    private static int distinctKnown(List<SnapshotItem> items, int k,
                                     Map<Long, String> categoryById) {
        return knownCategories(items, k, categoryById).size();
    }

    private static Set<String> knownCategories(List<SnapshotItem> items, int k,
                                               Map<Long, String> categoryById) {
        Set<String> categories = new HashSet<>();
        for (int i = 0; i < k; i++) {
            String category = categoryById.get(items.get(i).id());
            if (category != null) {
                categories.add(category);
            }
        }
        return categories;
    }

    /**
     * 优先换掉 TopK 中数量最多分类里排名最低的那一条。
     */
    private static int swapTarget(List<SnapshotItem> items, int k,
                                  Map<Long, String> categoryById) {
        if (k <= 0) {
            return -1;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            String category = categoryById.get(items.get(i).id());
            if (category != null) {
                counts.merge(category, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return k - 1;
        }
        String dominant = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        for (int i = k - 1; i >= 0; i--) {
            if (dominant != null && dominant.equals(categoryById.get(items.get(i).id()))) {
                return i;
            }
        }
        return k - 1;
    }
}
