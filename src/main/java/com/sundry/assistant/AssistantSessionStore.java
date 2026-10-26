package com.sundry.assistant;

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
 * 助手多轮会话：只存问答文本，不存工具结果集；key 绑定 userId。
 */
@Component
public class AssistantSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AssistantSessionStore.class);
    private static final String KEY = "assistant:session:%d:%s";
    private static final int MAX_CONTENT = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxTurns;

    public AssistantSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 @Value("${app.assistant.session-ttl:30m}") Duration ttl,
                                 @Value("${app.assistant.session-max-turns:3}") int maxTurns) {
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
            log.warn("助手会话读取失败 userId={}", userId, ex);
            return List.of();
        }
    }

    public void append(Long userId, String sessionId, String userMessage, String assistantReply) {
        if (sessionId == null) {
            return;
        }
        try {
            String key = key(userId, sessionId);
            redis.opsForList().rightPush(key, toJson("user", userMessage));
            redis.opsForList().rightPush(key, toJson("assistant", assistantReply));
            redis.opsForList().trim(key, -(long) maxTurns * 2, -1);
            redis.expire(key, ttl);
        } catch (RuntimeException ex) {
            log.warn("助手会话写入失败 userId={}", userId, ex);
        }
    }

    private Map<String, String> parse(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            String role = node.path("role").asText("");
            String content = node.path("content").asText("");
            if (role.isEmpty() || content.isEmpty()) {
                return null;
            }
            return Map.of("role", role, "content", content);
        } catch (RuntimeException ex) {
            log.warn("助手会话条目无法解析，已跳过");
            return null;
        }
    }

    private String toJson(String role, String content) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("content", truncate(content));
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("会话条目序列化失败", ex);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT);
    }

    private String key(Long userId, String sessionId) {
        return String.format(KEY, userId, sessionId);
    }
}
