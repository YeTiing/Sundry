package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.session.SessionIds;
import com.sundry.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 助手入口：规则快路径优先，未命中再让模型选工具；所有执行与权限都在 Java。
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{12,32}\\b");

    private static final String CLARIFY =
            "我还没学会这个问题的回答。你可以试试：我的订单、购物车、商品列表。";
    private static final String DEGRADED =
            "助手暂时不可用，你可以试试：我的订单、购物车、商品列表。";
    private static final String REJECT_MULTI = "我一次只能完成一个操作。";
    private static final String REJECT_UNKNOWN = "我暂时无法完成这个操作。";
    private static final String REJECT_ROUTE = "我打不开这个页面。";
    private static final String REJECT_ARGS = "我没能理解这个操作。";

    private final RouteCatalog catalog;
    private final AssistantChatLogMapper logMapper;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantClient client;
    private final AssistantToolRegistry registry;
    private final AssistantSessionStore sessionStore;
    private final ObjectMapper objectMapper;

    public AssistantService(RouteCatalog catalog, AssistantChatLogMapper logMapper,
                            AssistantRateLimiter rateLimiter, AssistantClient client,
                            AssistantToolRegistry registry, AssistantSessionStore sessionStore,
                            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.logMapper = logMapper;
        this.rateLimiter = rateLimiter;
        this.client = client;
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
    }

    public AssistantReply chat(AuthenticatedUser user, AssistantChatRequest request) {
        rateLimiter.check(user.id());
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        String message = request.message() == null ? "" : request.message().trim();
        String safeMessage = mask(message);
        String sessionId = SessionIds.normalize(request.sessionId());
        AssistantChatRequest normalized = new AssistantChatRequest(sessionId, request.message());

        RouteDefinition route = catalog.match(message, user.role());
        if (route != null) {
            audit(user, normalized, safeMessage, "RULE", "navigate", null, null,
                    route.name(), "OK", null, start, traceId);
            return remember(user.id(), sessionId, safeMessage, navigate(route, traceId));
        }
        List<Map<String, String>> history = sessionStore.history(user.id(), sessionId);
        return chatWithModel(user, normalized, message, safeMessage, history, traceId, start);
    }

    private AssistantReply chatWithModel(AuthenticatedUser user, AssistantChatRequest request,
                                         String message, String safeMessage,
                                         List<Map<String, String>> history,
                                         String traceId, long start) {
        AssistantLlmResponse response;
        try {
            response = client.chat(new AssistantLlmRequest(message, history,
                    user.role().name(), toolsFor(user.role())), traceId);
        } catch (RuntimeException ex) {
            log.warn("助手模型不可用 userId={} reason={}", user.id(), ex.getMessage());
            audit(user, request, safeMessage, "LLM", "clarify", null, null,
                    null, "DEGRADED", "AI_UNAVAILABLE", start, traceId);
            return clarify(DEGRADED, traceId);
        }

        if (response == null) {
            audit(user, request, safeMessage, "LLM", "clarify", null, null,
                    null, "DEGRADED", "AI_EMPTY_RESPONSE", start, traceId);
            return clarify(DEGRADED, traceId);
        }

        List<AssistantLlmResponse.ToolCall> calls = response.toolCalls();
        if (calls == null || calls.isEmpty()) {
            String content = response.content() == null ? "" : response.content().trim();
            if (content.isEmpty()) {
                audit(user, request, safeMessage, "LLM", "clarify", null, null,
                        null, "DEGRADED", "NO_CONTENT", start, traceId);
                return clarify(CLARIFY, traceId);
            }
            String answer = content.length() > 1000 ? content.substring(0, 1000) : content;
            audit(user, request, safeMessage, "LLM", "answer", null, null,
                    null, "OK", null, start, traceId);
            return remember(user.id(), request.sessionId(), safeMessage,
                    new AssistantReply("answer", answer, null, null, Map.of(), traceId));
        }

        if (calls.size() > 1) {
            audit(user, request, safeMessage, "LLM", "clarify", null, null,
                    null, "REJECTED", "MULTI_TOOL", start, traceId);
            return clarify(REJECT_MULTI, traceId);
        }

        AssistantLlmResponse.ToolCall call = calls.get(0);
        if (call == null) {
            audit(user, request, safeMessage, "LLM", "clarify", null, null,
                    null, "REJECTED", "UNKNOWN_TOOL", start, traceId);
            return clarify(REJECT_UNKNOWN, traceId);
        }
        if ("navigate".equals(call.name())) {
            return handleNavigate(user, request, safeMessage, call, start, traceId);
        }
        return handleBusinessTool(user, request, safeMessage, call, start, traceId);
    }

    private AssistantReply handleNavigate(AuthenticatedUser user, AssistantChatRequest request,
                                          String safeMessage,
                                          AssistantLlmResponse.ToolCall call,
                                          long start, String traceId) {
        JsonNode arguments = parseArguments(call.arguments());
        if (arguments == null) {
            audit(user, request, safeMessage, "LLM", "clarify", "navigate", null,
                    null, "REJECTED", "INVALID_ARGS", start, traceId);
            return clarify(REJECT_ARGS, traceId);
        }
        JsonNode routeNode = arguments.get("route");
        String routeName = routeNode != null && routeNode.isTextual()
                ? routeNode.asText().trim()
                : "";
        if (routeName.isEmpty()) {
            audit(user, request, safeMessage, "LLM", "clarify", "navigate", null,
                    null, "REJECTED", "INVALID_ARGS", start, traceId);
            return clarify(REJECT_ARGS, traceId);
        }
        RouteDefinition target = catalog.findByName(routeName, user.role());
        if (target == null) {
            audit(user, request, safeMessage, "LLM", "clarify", "navigate",
                    call.arguments(), null, "REJECTED", "FORBIDDEN_ROUTE", start, traceId);
            return clarify(REJECT_ROUTE, traceId);
        }
        audit(user, request, safeMessage, "LLM", "navigate", "navigate",
                call.arguments(), target.name(), "OK", null, start, traceId);
        return remember(user.id(), request.sessionId(), safeMessage, navigate(target, traceId));
    }

    private AssistantReply handleBusinessTool(AuthenticatedUser user, AssistantChatRequest request,
                                              String safeMessage,
                                              AssistantLlmResponse.ToolCall call,
                                              long start, String traceId) {
        AssistantTool tool = registry.find(call.name(), user.role());
        if (tool == null) {
            String reason = registry.exists(call.name()) ? "FORBIDDEN_TOOL" : "UNKNOWN_TOOL";
            audit(user, request, safeMessage, "LLM", "clarify", call.name(), null,
                    null, "REJECTED", reason, start, traceId);
            return clarify(REJECT_UNKNOWN, traceId);
        }
        JsonNode arguments = parseArguments(call.arguments());
        if (arguments == null) {
            audit(user, request, safeMessage, "LLM", "clarify", call.name(), null,
                    null, "REJECTED", "INVALID_ARGS", start, traceId);
            return clarify(REJECT_ARGS, traceId);
        }
        try {
            AssistantToolResult result = tool.execute(
                    new AssistantToolContext(user.id(), user.role()), arguments);
            audit(user, request, safeMessage, "LLM", "tool", call.name(),
                    call.arguments(), null, "OK", null, start, traceId);
            return remember(user.id(), request.sessionId(), safeMessage,
                    new AssistantReply("tool", result.reply(), null, null, Map.of(), traceId));
        } catch (AssistantToolException ex) {
            audit(user, request, safeMessage, "LLM", "clarify", call.name(),
                    call.arguments(), null, "REJECTED", ex.getCode(), start, traceId);
            return clarify(ex.getReply(), traceId);
        } catch (RuntimeException ex) {
            log.warn("助手工具执行失败 userId={} tool={}", user.id(), call.name(), ex);
            audit(user, request, safeMessage, "LLM", "clarify", call.name(),
                    call.arguments(), null, "REJECTED", "TOOL_ERROR", start, traceId);
            return clarify("这个操作暂时没有完成，请稍后再试。", traceId);
        }
    }

    private JsonNode parseArguments(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<AssistantToolSpec> toolsFor(Role role) {
        List<AssistantToolSpec> specs = new ArrayList<>();
        specs.add(navigateSpec(role));
        specs.addAll(registry.specsFor(role));
        return specs;
    }

    private AssistantToolSpec navigateSpec(Role role) {
        List<String> routes = catalog.visibleTo(role).stream()
                .map(RouteDefinition::name)
                .toList();
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "route", Map.of("type", "string", "enum", routes)),
                "required", List.of("route"));
        AssistantToolSpec.Function function = new AssistantToolSpec.Function(
                "navigate",
                "帮用户打开本项目内的某个页面。只在用户问『在哪』『怎么去』『打开』『进入』时使用。",
                parameters);
        return new AssistantToolSpec("function", function);
    }

    private AssistantReply navigate(RouteDefinition route, String traceId) {
        return new AssistantReply("navigate",
                "已为你找到「" + route.label() + "」入口，点击下方按钮打开。",
                route.name(), route.label(), Map.of(), traceId);
    }

    private AssistantReply clarify(String reply, String traceId) {
        return new AssistantReply("clarify", reply, null, null, Map.of(), traceId);
    }

    /**
     * 只记录成功轮；降级和拒绝不写入，避免把失败上下文带进下一轮。
     */
    private AssistantReply remember(Long userId, String sessionId, String userMessage,
                                    AssistantReply reply) {
        sessionStore.append(userId, sessionId, userMessage, reply.reply());
        return reply;
    }

    private void audit(AuthenticatedUser user, AssistantChatRequest request, String message,
                       String source, String action, String toolName, String arguments,
                       String route, String status, String rejectReason,
                       long start, String traceId) {
        try {
            AssistantChatLog entry = new AssistantChatLog();
            entry.setUserId(user.id());
            entry.setRole(user.role().name());
            entry.setSessionId(request.sessionId());
            entry.setMessage(message);
            entry.setSource(source);
            entry.setAction(action);
            entry.setToolName(toolName);
            entry.setArguments(arguments);
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
