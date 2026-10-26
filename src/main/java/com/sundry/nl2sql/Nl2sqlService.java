package com.sundry.nl2sql;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.common.session.SessionIds;
import com.sundry.nl2sql.ValidatedPlan.Dimension;
import com.sundry.nl2sql.ValidatedPlan.Metric;
import com.sundry.nl2sql.dto.Nl2sqlLogView;
import com.sundry.nl2sql.dto.Nl2sqlPlanRequest;
import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
import com.sundry.nl2sql.dto.Nl2sqlQueryRequest;
import com.sundry.nl2sql.dto.Nl2sqlQueryView;
import com.sundry.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NL2SQL 全链路：规划 → 校验 → 拼 SQL → 只读执行 → 审计。
 */
@Service
public class Nl2sqlService {

    private static final Logger log = LoggerFactory.getLogger(Nl2sqlService.class);
    private static final Set<String> STATUSES = Set.of(
            "SUCCESS", "REJECTED", "UNSUPPORTED", "DB_ERROR", "TIMEOUT", "AI_UNAVAILABLE");

    private final Nl2sqlProperties properties;
    private final SemanticLayerProvider provider;
    private final Nl2sqlClient client;
    private final Nl2sqlPlanValidator validator;
    private final Nl2sqlQueryBuilder builder;
    private final Nl2sqlQueryExecutor executor;
    private final Nl2sqlLogMapper logMapper;
    private final Nl2sqlRateLimiter rateLimiter;
    private final Nl2sqlSessionStore sessionStore;
    private final ObjectMapper objectMapper;

    public Nl2sqlService(Nl2sqlProperties properties, SemanticLayerProvider provider,
                         Nl2sqlClient client, Nl2sqlPlanValidator validator,
                         Nl2sqlQueryBuilder builder, Nl2sqlQueryExecutor executor,
                         Nl2sqlLogMapper logMapper, Nl2sqlRateLimiter rateLimiter,
                         Nl2sqlSessionStore sessionStore, ObjectMapper objectMapper) {
        this.properties = properties;
        this.provider = provider;
        this.client = client;
        this.validator = validator;
        this.builder = builder;
        this.executor = executor;
        this.logMapper = logMapper;
        this.rateLimiter = rateLimiter;
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 审计列表不依赖 enabled 开关：即使查询功能关闭，管理员也能看历史记录。
     */
    public PageResponse<Nl2sqlLogView> logs(int page, int size, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        String normalized = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "状态不合法");
        }
        long offset = (long) (safePage - 1) * safeSize;
        List<Nl2sqlLogView> items = logMapper.findPage(normalized, offset, safeSize).stream()
                .map(log -> new Nl2sqlLogView(log.getId(), log.getUserId(), log.getRole(),
                        log.getSessionId(), log.getQuestion(), log.getRowCount(),
                        log.getLatencyMs(), log.getStatus(), log.getRejectReason(),
                        log.getTraceId(), log.getCreatedAt()))
                .toList();
        return new PageResponse<>(items, logMapper.count(normalized), safePage, safeSize);
    }

    public Nl2sqlQueryView query(AuthenticatedUser user, Nl2sqlQueryRequest request) {
        if (user.role() != Role.ADMIN && user.role() != Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.NL2SQL_DISABLED);
        }
        String sessionId = SessionIds.normalize(request.sessionId());
        Nl2sqlQueryRequest normalized = new Nl2sqlQueryRequest(request.question(), sessionId);
        rateLimiter.check(user.id());
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        String question = request.question().trim();
        List<Map<String, String>> history = sessionStore.history(user.id(), sessionId);

        Nl2sqlPlanResponse planResponse;
        try {
            planResponse = client.plan(new Nl2sqlPlanRequest(question, user.role().name(),
                    provider.layer().vocabulary(), history), traceId);
        } catch (RuntimeException ex) {
            log.warn("NL2SQL 规划失败 userId={} reason={}", user.id(), ex.getMessage());
            audit(user, normalized, question, null, null, 0,
                    "AI_UNAVAILABLE", "AI_UNAVAILABLE", start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_UNAVAILABLE);
        }

        if (planResponse == null) {
            audit(user, normalized, question, null, null, 0,
                    "AI_UNAVAILABLE", "EMPTY_RESPONSE", start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_UNAVAILABLE);
        }
        String planJson = planJson(planResponse.plan());
        if (planResponse.unsupported()) {
            String reason = planResponse.reason() == null || planResponse.reason().isBlank()
                    ? "词汇表无法表达该问题" : planResponse.reason();
            audit(user, normalized, question, planJson, null, 0,
                    "UNSUPPORTED", reason, start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_UNSUPPORTED, reason);
        }

        ValidatedPlan plan;
        try {
            plan = validator.validate(planResponse.plan());
        } catch (Nl2sqlValidationException ex) {
            audit(user, normalized, question, planJson, null, 0,
                    "REJECTED", ex.getCode() + ": " + ex.getMessage(), start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_PLAN_REJECTED, ex.getMessage());
        }

        QuerySpec spec = builder.build(plan);
        List<Map<String, Object>> rows;
        try {
            rows = executor.execute(spec, properties.getQueryTimeoutSeconds(),
                    properties.getMaxRows());
        } catch (QueryTimeoutException ex) {
            audit(user, normalized, question, planJson, spec.sql(), 0,
                    "TIMEOUT", "QUERY_TIMEOUT", start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_QUERY_FAILED);
        } catch (DataAccessException | Nl2sqlException ex) {
            log.warn("NL2SQL 执行失败 userId={} reason={}", user.id(), ex.getMessage());
            audit(user, normalized, question, planJson, spec.sql(), 0,
                    "DB_ERROR", "DB_ERROR", start, traceId);
            throw new BusinessException(ErrorCode.NL2SQL_QUERY_FAILED);
        }

        audit(user, normalized, question, planJson, spec.sql(),
                rows.size(), "SUCCESS", null, start, traceId);
        sessionStore.append(user.id(), sessionId, question, planJson, rows.size());
        return view(plan, rows, planResponse.plan(), traceId, start);
    }

    private Nl2sqlQueryView view(ValidatedPlan plan, List<Map<String, Object>> rows,
                                 Nl2sqlPlanResponse.Plan rawPlan, String traceId, long start) {
        List<Nl2sqlQueryView.Column> columns = new ArrayList<>();
        for (Dimension dimension : plan.dimensions()) {
            columns.add(new Nl2sqlQueryView.Column(dimension.key(), dimension.label(), null));
        }
        for (Metric metric : plan.metrics()) {
            columns.add(new Nl2sqlQueryView.Column(metric.key(), metric.label(), metric.unit()));
        }

        Map<String, Object> planView = new LinkedHashMap<>();
        planView.put("metrics", plan.metrics().stream().map(Metric::key).toList());
        planView.put("dimensions", plan.dimensions().stream().map(Dimension::key).toList());
        planView.put("filters", plan.filters().stream()
                .map(filter -> Map.of("key", filter.key(), "value", filter.value()))
                .toList());
        Map<String, Object> timeView = new LinkedHashMap<>();
        timeView.put("preset", rawPlan.time() == null ? null : rawPlan.time().preset());
        timeView.put("start", plan.time().start().toString());
        timeView.put("end", plan.time().end().toString());
        planView.put("time", timeView);
        planView.put("granularity", rawPlan.granularity() == null ? "day" : rawPlan.granularity());
        planView.put("limit", plan.limit());

        String answer;
        if (rows.isEmpty()) {
            answer = "没有符合条件的数据。";
        } else if (rows.size() == 1 && plan.metrics().size() == 1
                && plan.dimensions().isEmpty()) {
            Metric metric = plan.metrics().get(0);
            Object value = rows.get(0).get(metric.key());
            answer = metric.label() + "：" + value
                    + (metric.unit() == null || metric.unit().isBlank()
                    ? "" : " " + metric.unit()) + "。";
        } else {
            answer = "共 " + rows.size() + " 行结果。";
        }

        long latency = System.currentTimeMillis() - start;
        return new Nl2sqlQueryView(answer, columns, rows, planView, traceId,
                rows.size(), latency);
    }

    private String planJson(Object plan) {
        if (plan == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void audit(AuthenticatedUser user, Nl2sqlQueryRequest request, String question,
                       String planJson, String sql, int rowCount, String status,
                       String reason, long start, String traceId) {
        try {
            Nl2sqlLog entry = new Nl2sqlLog();
            entry.setUserId(user.id());
            entry.setRole(user.role().name());
            entry.setSessionId(request.sessionId());
            entry.setQuestion(question);
            entry.setPlanJson(planJson);
            entry.setSqlText(sql);
            entry.setRowCount(rowCount);
            entry.setLatencyMs((int) Math.min(Integer.MAX_VALUE,
                    System.currentTimeMillis() - start));
            entry.setStatus(status);
            entry.setRejectReason(reason == null ? null
                    : reason.substring(0, Math.min(200, reason.length())));
            entry.setTraceId(traceId);
            logMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("NL2SQL 审计写入失败 userId={} status={}", user.id(), status, ex);
        }
    }
}
