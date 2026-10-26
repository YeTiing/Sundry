package com.sundry.nl2sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NL2SQL 多轮会话：只存问题、计划和行数，不存结果集；key 绑定 userId。
 */
@Component
public class Nl2sqlSessionStore {

    private static final Logger log = LoggerFactory.getLogger(Nl2sqlSessionStore.class);
    private static final String KEY = "nl2sql:session:%d:%s";
    private static final int MAX_QUESTION = 500;
    private static final int MAX_PLAN = 2000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxTurns;

    public Nl2sqlSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                              @Value("${app.nl2sql.session-ttl:30m}") Duration ttl,
                              @Value("${app.nl2sql.session-max-turns:3}") int maxTurns) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.maxTurns = Math.max(1, maxTurns);
    }

    public List<Map<String, String>> history(Long userId, String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        try {
            List<String> values = redis.opsForList().range(key(userId, sessionId), 0, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<Map<String, String>> history = new ArrayList<>(values.size());
            for (String value : values) {
                Map<String, String> entry = parse(value);
                if (entry != null) {
                    history.add(entry);
                }
            }
            return history;
        } catch (RuntimeException ex) {
            log.warn("NL2SQL 会话读取失败 userId={}", userId, ex);
            return List.of();
        }
    }

    public void append(Long userId, String sessionId, String question,
                       String planJson, int rowCount) {
        if (sessionId == null || planJson == null) {
            return;
        }
        try {
            String key = key(userId, sessionId);
            redis.opsForList().rightPush(key, toJson(question, planJson, rowCount));
            redis.opsForList().trim(key, -maxTurns, -1);
            redis.expire(key, ttl);
        } catch (RuntimeException ex) {
            log.warn("NL2SQL 会话写入失败 userId={}", userId, ex);
        }
    }

    private Map<String, String> parse(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            String question = node.path("question").asText("");
            String plan = node.path("plan").asText("");
            if (question.isEmpty() || plan.isEmpty()) {
                return null;
            }
            return Map.of(
                    "question", question,
                    "plan", plan,
                    "rowCount", node.path("rowCount").asText("0"));
        } catch (RuntimeException ex) {
            log.warn("NL2SQL 会话条目无法解析，已跳过");
            return null;
        }
    }

    private String toJson(String question, String planJson, int rowCount) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("question", truncate(question, MAX_QUESTION));
        entry.put("plan", truncate(planJson, MAX_PLAN));
        entry.put("rowCount", String.valueOf(rowCount));
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("会话条目序列化失败", ex);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String key(Long userId, String sessionId) {
        return String.format(KEY, userId, sessionId);
    }
}
