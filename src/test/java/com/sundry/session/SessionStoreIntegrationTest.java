package com.sundry.session;

import com.sundry.assistant.AssistantSessionStore;
import com.sundry.nl2sql.Nl2sqlSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话存储本身的行为：读写、截断、TTL、用户隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionStoreIntegrationTest {

    private static final long USER_A = 9_000_001L;
    private static final long USER_B = 9_000_002L;

    @Autowired
    private AssistantSessionStore assistantStore;

    @Autowired
    private Nl2sqlSessionStore nl2sqlStore;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        clearSessions();
    }

    @AfterEach
    void tearDown() {
        clearSessions();
    }

    @Test
    void assistantStoresAndReadsBack() {
        assistantStore.append(USER_A, "s1", "你好", "在呢");

        assertThat(assistantStore.history(USER_A, "s1")).containsExactly(
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "在呢"));
        assertThat(redis.getExpire("assistant:session:9000001:s1"))
                .isBetween(1L, 1800L);
    }

    @Test
    void assistantKeepsThreeRounds() {
        for (int i = 1; i <= 4; i++) {
            assistantStore.append(USER_A, "s2", "问" + i, "答" + i);
        }

        List<Map<String, String>> history = assistantStore.history(USER_A, "s2");
        assertThat(history).hasSize(6);
        assertThat(history.get(0)).containsEntry("content", "问2");
        assertThat(history.get(5)).containsEntry("content", "答4");
    }

    @Test
    void assistantIsolatesUsers() {
        assistantStore.append(USER_A, "shared", "私密问题", "回答");

        assertThat(assistantStore.history(USER_B, "shared")).isEmpty();
        assertThat(assistantStore.history(USER_A, "shared")).hasSize(2);
    }

    @Test
    void nl2sqlStoresPlanAndRowsOnly() {
        nl2sqlStore.append(USER_A, "n1", "最近30天销售额", "{\"metrics\":[\"gmv\"]}", 3);

        assertThat(nl2sqlStore.history(USER_A, "n1")).containsExactly(Map.of(
                "question", "最近30天销售额",
                "plan", "{\"metrics\":[\"gmv\"]}",
                "rowCount", "3"));
        assertThat(redis.getExpire("nl2sql:session:9000001:n1"))
                .isBetween(1L, 1800L);
    }

    @Test
    void nl2sqlKeepsThreeTurnsAndIsolatesUsers() {
        for (int i = 1; i <= 4; i++) {
            nl2sqlStore.append(USER_A, "n2", "问题" + i, "{\"m\":" + i + "}", i);
        }

        List<Map<String, String>> history = nl2sqlStore.history(USER_A, "n2");
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).containsEntry("question", "问题2");
        assertThat(history.get(2)).containsEntry("question", "问题4");
        assertThat(nl2sqlStore.history(USER_B, "n2")).isEmpty();
    }

    @Test
    void nullSessionDoesNotWrite() {
        assistantStore.append(USER_A, null, "问", "答");
        nl2sqlStore.append(USER_A, null, "问", "{}", 1);

        assertThat(redis.keys("assistant:session:*")).isEmpty();
        assertThat(redis.keys("nl2sql:session:*")).isEmpty();
    }

    private void clearSessions() {
        clear(redis.keys("assistant:session:*"));
        clear(redis.keys("nl2sql:session:*"));
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
