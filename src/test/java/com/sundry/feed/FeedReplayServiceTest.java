package com.sundry.feed;

import com.sundry.feed.dto.FeedReplayView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedReplayServiceTest {

    @Mock
    private FeedMetricsMapper mapper;

    private FeedReplayService service;

    @BeforeEach
    void setUp() {
        service = new FeedReplayService(mapper, new ObjectMapper());
    }

    @Test
    void comparesRuleAndAiOnClickedItems() {
        FeedMetricsMapper.ReplaySnapshotRow row = new FeedMetricsMapper.ReplaySnapshotRow();
        row.setId(1L);
        row.setUserId(2L);
        row.setCreatedAt(LocalDateTime.now().minusHours(1));
        row.setCandidateSnapshot("""
                {
                  "candidates": [
                    {"id": 1, "ruleRank": 1},
                    {"id": 2, "ruleRank": 2},
                    {"id": 3, "ruleRank": 3}
                  ],
                  "variants": [
                    {"strategy": "PREF", "buildId": "PREF:1",
                     "model": "deepseek", "items": [3, 1, 2]},
                    {"strategy": "EXPLORE", "buildId": "EXPLORE:1",
                     "model": "rule-fallback", "items": [1, 2, 3]}
                  ]
                }
                """);
        when(mapper.findReplaySnapshots(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.findReplayClicks(anyList(), any())).thenReturn(List.of(
                click(1L, "PREF:1", 1L),
                click(2L, "PREF:1", 2L)));

        FeedReplayView view = service.replay(7, 2);

        assertThat(view.snapshots()).isEqualTo(1);
        assertThat(view.samples()).isEqualTo(1);
        assertThat(view.candidateCoverage()).isEqualTo(1.0);

        FeedReplayView.StrategyReplay pref = view.byStrategy().stream()
                .filter(item -> item.strategy().equals("PREF"))
                .findFirst().orElseThrow();
        assertThat(pref.samples()).isEqualTo(1);
        assertThat(pref.rule().recallAtK()).isEqualTo(1.0);
        assertThat(pref.rule().ndcgAtK()).isEqualTo(1.0);
        assertThat(pref.rule().lastClickHitRate()).isEqualTo(1.0);
        assertThat(pref.ai().recallAtK()).isEqualTo(0.5);
        assertThat(pref.ai().ndcgAtK()).isEqualTo(0.3869);
        assertThat(pref.ai().lastClickHitRate()).isEqualTo(0.0);

        FeedReplayView.StrategyReplay all = view.byStrategy().stream()
                .filter(item -> item.strategy().equals("ALL"))
                .findFirst().orElseThrow();
        assertThat(all.samples()).isEqualTo(1);
        assertThat(all.rule().recallAtK()).isEqualTo(1.0);
        assertThat(all.ai().recallAtK()).isEqualTo(0.5);
        assertThat(view.byStrategy())
                .extracting(FeedReplayView.StrategyReplay::strategy)
                .doesNotContain("EXPLORE");
    }

    @Test
    void emptyDataReturnsZeroSamples() {
        when(mapper.findReplaySnapshots(any(), anyInt())).thenReturn(List.of());

        FeedReplayView view = service.replay(7, 10);

        assertThat(view.snapshots()).isZero();
        assertThat(view.samples()).isZero();
        assertThat(view.candidateCoverage()).isZero();
        assertThat(view.byStrategy()).isEmpty();
    }

    private FeedMetricsMapper.ReplayClickRow click(long id, String buildId, long productId) {
        FeedMetricsMapper.ReplayClickRow row = new FeedMetricsMapper.ReplayClickRow();
        row.setId(id);
        row.setBuildId(buildId);
        row.setProductId(productId);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
