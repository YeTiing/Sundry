package com.sundry.ai;

import com.sundry.ai.FeedSnapshot.SnapshotItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FeedDiversityRankerTest {

    @Test
    void breaksLongRunsAndPreservesMembership() {
        List<SnapshotItem> input = List.of(
                item(1), item(2), item(3), item(4), item(5), item(6), item(7));
        Map<Long, String> categories = Map.of(
                1L, "A", 2L, "A", 3L, "A", 4L, "A", 5L, "A", 6L, "B", 7L, "C");

        FeedDiversityRanker.Result result = FeedDiversityRanker.reorder(
                input, categories, 3, 3, 5);

        assertThat(result.items()).extracting(SnapshotItem::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(maxRun(result.items(), categories)).isLessThanOrEqualTo(3);
        assertThat(distinct(result.items(), categories, 5)).isGreaterThanOrEqualTo(3);
        assertThat(result.moved()).isPositive();
    }

    @Test
    void alreadyDiverseIsUnchanged() {
        List<SnapshotItem> input = List.of(
                item(1), item(2), item(3), item(4), item(5), item(6));
        Map<Long, String> categories = Map.of(
                1L, "A", 2L, "B", 3L, "C", 4L, "A", 5L, "B", 6L, "C");

        FeedDiversityRanker.Result result = FeedDiversityRanker.reorder(
                input, categories, 3, 3, 6);

        assertThat(result.items()).extracting(SnapshotItem::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(result.changed()).isFalse();
    }

    @Test
    void insufficientCategoriesRelaxesWithoutLosingItems() {
        List<SnapshotItem> input = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            input.add(item(i));
        }
        Map<Long, String> categories = new java.util.HashMap<>();
        for (long i = 1; i <= 10; i++) {
            categories.put(i, "A");
        }

        FeedDiversityRanker.Result result = FeedDiversityRanker.reorder(
                input, categories, 3, 3, 5);

        assertThat(result.items()).hasSize(10);
        assertThat(result.items()).extracting(SnapshotItem::id)
                .containsExactlyInAnyOrderElementsOf(
                        input.stream().map(SnapshotItem::id).toList());
        assertThat(result.relaxed()).isPositive();
    }

    @Test
    void minCategoriesPullsFromTail() {
        List<SnapshotItem> input = List.of(
                item(1), item(2), item(3), item(4), item(5), item(6), item(7));
        Map<Long, String> categories = Map.of(
                1L, "A", 2L, "A", 3L, "A", 4L, "A", 5L, "A", 6L, "B", 7L, "C");

        // 跳过连续约束，只验证 TopK 分类补位
        FeedDiversityRanker.Result result = FeedDiversityRanker.reorder(
                input, categories, 0, 3, 5);

        assertThat(distinct(result.items(), categories, 5)).isGreaterThanOrEqualTo(3);
        assertThat(result.items()).extracting(SnapshotItem::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test
    void unknownCategoriesDoNotCount() {
        List<SnapshotItem> input = List.of(item(1), item(2), item(3));
        FeedDiversityRanker.Result result = FeedDiversityRanker.reorder(
                input, Map.of(), 3, 2, 3);

        assertThat(result.items()).hasSize(3);
        assertThat(result.relaxed()).isPositive();
    }

    private SnapshotItem item(long id) {
        return new SnapshotItem(id, null);
    }

    private int maxRun(List<SnapshotItem> items, Map<Long, String> categories) {
        int max = 0;
        int run = 0;
        String last = null;
        for (SnapshotItem item : items) {
            String category = categories.get(item.id());
            if (category != null && category.equals(last)) {
                run++;
            } else {
                run = 1;
                last = category;
            }
            max = Math.max(max, run);
        }
        return max;
    }

    private int distinct(List<SnapshotItem> items, Map<Long, String> categories, int k) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < Math.min(k, items.size()); i++) {
            String category = categories.get(items.get(i).id());
            if (category != null) {
                set.add(category);
            }
        }
        return set.size();
    }
}
