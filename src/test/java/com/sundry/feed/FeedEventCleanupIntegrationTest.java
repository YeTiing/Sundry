package com.sundry.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期曝光 14 天、点击 90 天；近期数据保留。
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedEventCleanupIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FeedEventCleanupProcessor processor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM feed_event");
    }

    @Test
    void deletesExpiredEventsAndKeepsRecent() {
        insert("old-exposure", "EXPOSURE", 15);
        insert("new-exposure", "EXPOSURE", 13);
        insert("old-click", "CLICK", 91);
        insert("new-click", "CLICK", 89);
        insert("very-old-exposure", "EXPOSURE", 100);
        insert("very-old-click", "CLICK", 200);

        FeedEventCleanupProcessor.CleanupResult result = processor.cleanup();

        assertThat(result.exposures()).isEqualTo(2);
        assertThat(result.clicks()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT trace_id FROM feed_event ORDER BY trace_id", String.class))
                .containsExactly("new-click", "new-exposure");
    }

    private void insert(String traceId, String type, int daysAgo) {
        jdbcTemplate.update("""
                INSERT INTO feed_event
                    (trace_id, user_id, event_type, algorithm, build_id,
                     product_id, category_id, item_position, created_at)
                VALUES (?, 1, ?, 'RULE', NULL, 1, 1, 1, ?)
                """, traceId, type,
                Timestamp.valueOf(LocalDateTime.now().minusDays(daysAgo)));
    }
}
