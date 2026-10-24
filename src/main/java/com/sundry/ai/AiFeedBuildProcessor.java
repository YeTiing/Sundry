package com.sundry.ai;

import com.sundry.ai.FeedSnapshot.SnapshotItem;
import com.sundry.ai.dto.RerankRequest;
import com.sundry.ai.dto.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 后台构建 AI 推流快照。测试环境关闭定时任务，由测试直接调用。
 * 同一个用户同一时刻只有一个线程构建；旧 buildId 的结果不会覆盖新请求。
 */
@Component
public class AiFeedBuildProcessor {

    private static final Logger log = LoggerFactory.getLogger(AiFeedBuildProcessor.class);

    private final AiFeedStore store;
    private final AiFeedClient client;
    private final AiFeedProfileService profileService;
    private final AiFeedLogMapper logMapper;
    private final AiProperties properties;

    public AiFeedBuildProcessor(AiFeedStore store, AiFeedClient client,
                                AiFeedProfileService profileService,
                                AiFeedLogMapper logMapper, AiProperties properties) {
        this.store = store;
        this.client = client;
        this.profileService = profileService;
        this.logMapper = logMapper;
        this.properties = properties;
    }

    public int buildPending() {
        int batch = Math.max(1, properties.getFeedBatchSize());
        for (int i = 0; i < batch; i++) {
            if (store.moveToProcessing() == null) {
                break;
            }
        }

        List<String> processing = store.processing(batch * 4);
        if (processing == null || processing.isEmpty()) {
            return 0;
        }

        int handled = 0;
        for (String value : processing) {
            Long userId = parse(value);
            if (userId == null) {
                store.removeProcessing(value);
                continue;
            }
            if (processOne(userId)) {
                store.removeProcessing(value);
            }
            handled++;
        }
        return handled;
    }

    private boolean processOne(Long userId) {
        // 先清排队标记：处理期间用户再次点"换一批"可以重新入队
        store.clearQueued(userId);
        String pending = store.pending(userId);
        if (pending == null) {
            store.clearAttempts(userId);
            return true;
        }

        String lockToken = UUID.randomUUID().toString();
        if (!store.lock(userId, lockToken)) {
            return false;
        }

        String traceId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        int candidateCount = 0;

        try {
            AiFeedProfileService.CandidateBundle bundle = profileService.build(userId);
            candidateCount = bundle.candidates().size();
            if (bundle.candidates().isEmpty()) {
                throw new AiFeedException("NO_CANDIDATES");
            }

            RerankResponse response = client.rerank(new RerankRequest(
                    userId, bundle.profile(), bundle.candidates(),
                    properties.getFeedRerankLimit()), traceId);

            List<SnapshotItem> items = merge(response.items(), bundle);
            if (items.isEmpty()) {
                throw new AiFeedException("NO_VALID_ITEMS");
            }

            if (!pending.equals(store.pending(userId))) {
                audit(userId, traceId, response.model(), response.promptVersion(),
                        candidateCount, items.size(), start, "STALE", "superseded");
                return true;
            }

            store.saveSnapshot(userId, new FeedSnapshot(pending, LocalDateTime.now(),
                    response.model(), response.promptVersion(), items));
            store.clearPending(userId);
            store.clearAttempts(userId);
            audit(userId, traceId, response.model(), response.promptVersion(),
                    candidateCount, items.size(), start, "SUCCESS", null);
            return true;
        } catch (RuntimeException ex) {
            long attempt = store.nextAttempt(userId);
            String reason = messageOf(ex);
            if (attempt >= properties.getMaxAttempts()) {
                store.clearPending(userId);
                store.clearAttempts(userId);
                audit(userId, traceId, null, null, candidateCount, 0,
                        start, "FALLBACK", reason);
                log.warn("AI 推流失败，已降级 userId={} attempt={} reason={}",
                        userId, attempt, reason);
                return true;
            }
            audit(userId, traceId, null, null, candidateCount, 0, start, "ERROR", reason);
            log.warn("AI 推流失败，稍后重试 userId={} attempt={}", userId, attempt, ex);
            return false;
        } finally {
            store.unlock(userId, lockToken);
        }
    }

    private List<SnapshotItem> merge(List<RerankResponse.RerankItem> raw,
                                     AiFeedProfileService.CandidateBundle bundle) {
        Map<Long, String> allowed = new LinkedHashMap<>();
        for (RerankRequest.Candidate candidate : bundle.candidates()) {
            allowed.put(candidate.id(), candidate.name());
        }

        Set<Long> seen = new HashSet<>();
        List<SnapshotItem> items = new ArrayList<>();
        if (raw != null) {
            for (RerankResponse.RerankItem item : raw) {
                if (item == null || item.id() == null
                        || !allowed.containsKey(item.id()) || !seen.add(item.id())) {
                    continue;
                }
                items.add(new SnapshotItem(item.id(), sanitize(item.reason())));
                if (items.size() >= properties.getFeedRerankLimit()) {
                    break;
                }
            }
        }

        // 模型返回不足时，用规则顺序补齐候选和尾部
        for (RerankRequest.Candidate candidate : bundle.candidates()) {
            if (seen.add(candidate.id())) {
                items.add(new SnapshotItem(candidate.id(), null));
            }
        }
        for (Long id : bundle.tail()) {
            if (seen.add(id)) {
                items.add(new SnapshotItem(id, null));
            }
        }
        return items;
    }

    private String sanitize(String reason) {
        if (reason == null) {
            return null;
        }
        String clean = reason.replaceAll("\\p{Cntrl}", "").trim();
        if (clean.isEmpty()) {
            return null;
        }
        int length = clean.codePointCount(0, clean.length());
        if (length <= 40) {
            return clean;
        }
        return clean.substring(0, clean.offsetByCodePoints(0, 40));
    }

    private void audit(Long userId, String traceId, String model, String promptVersion,
                       int candidateCount, int returnedCount, long start,
                       String status, String reason) {
        try {
            AiFeedLog entry = new AiFeedLog();
            entry.setUserId(userId);
            entry.setTraceId(traceId);
            entry.setModel(model);
            entry.setPromptVersion(promptVersion);
            entry.setCandidateCount(candidateCount);
            entry.setReturnedCount(returnedCount);
            entry.setLatencyMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
            entry.setStatus(status);
            entry.setFallbackReason(reason == null ? null : reason.substring(0, Math.min(200, reason.length())));
            logMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("AI 审计写入失败 userId={} status={}", userId, status, ex);
        }
    }

    private Long parse(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String messageOf(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
