package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 助手入口。L0 只做规则导航；未命中统一走 clarify，L1 再接模型。
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{12,32}\\b");
    private static final String CLARIFY =
            "我还没学会这个问题的回答。你可以试试：我的订单、购物车、商品列表。";

    private final RouteCatalog catalog;
    private final AssistantChatLogMapper logMapper;
    private final AssistantRateLimiter rateLimiter;

    public AssistantService(RouteCatalog catalog, AssistantChatLogMapper logMapper,
                            AssistantRateLimiter rateLimiter) {
        this.catalog = catalog;
        this.logMapper = logMapper;
        this.rateLimiter = rateLimiter;
    }

    public AssistantReply chat(AuthenticatedUser user, AssistantChatRequest request) {
        rateLimiter.check(user.id());
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        String message = request.message() == null ? "" : request.message().trim();
        String safeMessage = mask(message);

        RouteDefinition route = catalog.match(message, user.role());
        if (route != null) {
            audit(user, request, safeMessage, "RULE", "navigate", null, route.name(),
                    "OK", null, start, traceId);
            return new AssistantReply("navigate",
                    "已为你找到「" + route.label() + "」入口，点击下方按钮打开。",
                    route.name(), route.label(), Map.of(), traceId);
        }

        audit(user, request, safeMessage, "RULE", "clarify", null, null,
                "DEGRADED", "NO_MATCH", start, traceId);
        return new AssistantReply("clarify", CLARIFY, null, null, Map.of(), traceId);
    }

    private void audit(AuthenticatedUser user, AssistantChatRequest request, String message,
                       String source, String action, String toolName, String route,
                       String status, String rejectReason, long start, String traceId) {
        try {
            AssistantChatLog entry = new AssistantChatLog();
            entry.setUserId(user.id());
            entry.setRole(user.role().name());
            entry.setSessionId(request.sessionId());
            entry.setMessage(message);
            entry.setSource(source);
            entry.setAction(action);
            entry.setToolName(toolName);
            entry.setRoute(route);
            entry.setStatus(status);
            entry.setRejectReason(rejectReason);
            entry.setLatencyMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
            entry.setTraceId(traceId);
            logMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("助手审计写入失败 userId={} action={}", user.id(), action, ex);
        }
    }

    private String mask(String text) {
        String masked = PHONE.matcher(text).replaceAll(match ->
                match.group().substring(0, 3) + "****" + match.group().substring(7));
        return LONG_DIGITS.matcher(masked).replaceAll(match -> {
            String digits = match.group();
            return digits.substring(0, 4) + "****" + digits.substring(digits.length() - 4);
        });
    }
}
