package com.sundry.ai;

import com.sundry.ai.FeedSnapshot.SnapshotItem;
import com.sundry.ai.dto.RerankRequest;
import com.sundry.ai.dto.RerankResponse;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台构建 AI 推流快照。测试环境关闭定时任务，由测试直接调用。
 * 同一个用户同一时刻只有一个线程构建；旧 buildId 的结果不会覆盖新请求。
 */
@Component
public class AiFeedBuildProcessor {

    private static final Logger log = LoggerFactory.getLogger(AiFeedBuildProcessor.class);

    private static final String STRATEGY_PREF = "PREF";
    private static final String STRATEGY_EXPLORE = "EXPLORE";
    private static final String STRATEGY_PRICE = "PRICE";

    private final ExecutorService variantExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "ai-variant-builder");
        thread.setDaemon(true);
        return thread;
    });

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

            List<VariantBuild> variants = buildVariants(userId, bundle, traceId);
            if (variants.isEmpty()) {
                throw new AiFeedException("NO_VALID_ITEMS");
            }

            if (!pending.equals(store.pending(userId))) {
                audit(userId, traceId, variants.get(0).model(), variants.get(0).promptVersion(),
                        candidateCount, variants.get(0).llmCount(), start, "STALE", "superseded");
                return true;
            }

            LocalDateTime builtAt = LocalDateTime.now();
            List<FeedSnapshot> snapshots = new ArrayList<>(variants.size());
            for (VariantBuild variant : variants) {
                snapshots.add(new FeedSnapshot(variant.buildId(), builtAt, variant.model(),
                        variant.promptVersion(), variant.items(), variant.strategy()));
            }
            store.pushNextVariants(userId, snapshots);

            VariantBuild first = variants.get(0);
            store.clearPending(userId);
            store.clearAttempts(userId);
            audit(userId, traceId, first.model(), first.promptVersion(),
                    candidateCount, first.llmCount(), start, "SUCCESS", null);
            return true;
        } catch (RuntimeException ex) {
            long attempt = store.nextAttempt(userId);
            String reason = messageOf(ex);
            if (attempt >= properties.getMaxAttempts()) {
                // 保留失败计数作为熔断标记，5 分钟内不再自动重试；成功时清除。
                store.clearPending(userId);
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

    @PreDestroy
    void shutdown() {
        variantExecutor.shutdownNow();
    }

    private List<VariantBuild> buildVariants(Long userId,
                                             AiFeedProfileService.CandidateBundle bundle,
                                             String traceId) {
        List<CompletableFuture<VariantBuild>> futures = new ArrayList<>();
        for (String strategy : strategiesFor(userId)) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> buildVariant(userId, bundle, strategy, traceId), variantExecutor));
        }

        List<VariantBuild> variants = new ArrayList<>();
        for (CompletableFuture<VariantBuild> future : futures) {
            try {
                VariantBuild variant = future.join();
                if (variant != null) {
                    variants.add(variant);
                }
            } catch (CompletionException ex) {
                log.warn("AI 变体构建失败 userId={} reason={}",
                        userId, reasonOf(ex.getCause()));
            }
        }
        dedupHeads(variants);
        return variants;
    }

    private VariantBuild buildVariant(Long userId, AiFeedProfileService.CandidateBundle bundle,
                                      String strategy, String traceId) {
        RerankResponse response = client.rerank(new RerankRequest(
                userId, bundle.profile(), bundle.candidates(),
                properties.getFeedRerankLimit(), strategy), traceId);
        MergeResult merged = merge(response.items(), bundle);
        if (merged.items().isEmpty()) {
            return null;
        }
        return new VariantBuild(strategy + ":" + UUID.randomUUID(), strategy,
                response.model(), response.promptVersion(), merged.items(), merged.llmCount());
    }

    /**
     * PREF 固定第一份；其余在 EXPLORE / PRICE 之间按用户轮换，保证两套策略都有样本。
     */
    private List<String> strategiesFor(Long userId) {
        int count = Math.max(1, properties.getFeedVariantCount());
        List<String> strategies = new ArrayList<>(count);
        strategies.add(STRATEGY_PREF);
        boolean exploreFirst = Math.floorMod(Long.hashCode(userId), 2) == 0;
        for (int i = 1; i < count; i++) {
            boolean explore = (i % 2 == 1) == exploreFirst;
            strategies.add(explore ? STRATEGY_EXPLORE : STRATEGY_PRICE);
        }
        return strategies;
    }

    /**
     * 第 k 份变体的头部不能与前 k-1 份重复：冲突项与靠后的候选交换。
     */
    private void dedupHeads(List<VariantBuild> variants) {
        int head = Math.max(0, properties.getFeedVariantDedupHead());
        if (head == 0 || variants.size() < 2) {
            return;
        }
        Set<Long> used = new HashSet<>();
        for (VariantBuild variant : variants) {
            List<SnapshotItem> items = variant.items();
            int boundary = Math.min(head, items.size());
            Set<Long> current = new HashSet<>();
            for (int i = 0; i < boundary; i++) {
                Long id = items.get(i).id();
                if (used.contains(id) || current.contains(id)) {
                    int swap = findSwap(items, boundary, used, current);
                    if (swap >= 0) {
                        SnapshotItem replaced = items.get(i);
                        items.set(i, items.get(swap));
                        items.set(swap, replaced);
                        id = items.get(i).id();
                    }
                }
                current.add(id);
            }
            used.addAll(current);
        }
    }

    private int findSwap(List<SnapshotItem> items, int boundary,
                         Set<Long> used, Set<Long> current) {
        for (int i = boundary; i < items.size(); i++) {
            Long id = items.get(i).id();
            if (!used.contains(id) && !current.contains(id)) {
                return i;
            }
        }
        return -1;
    }

    private MergeResult merge(List<RerankResponse.RerankItem> raw,
                              AiFeedProfileService.CandidateBundle bundle) {
        Map<Long, String> allowed = new LinkedHashMap<>();
        for (RerankRequest.Candidate candidate : bundle.candidates()) {
            allowed.put(candidate.id(), candidate.name());
        }

        Set<Long> seen = new HashSet<>();
        List<SnapshotItem> items = new ArrayList<>();
        int llmCount = 0;
        if (raw != null) {
            for (RerankResponse.RerankItem item : raw) {
                if (item == null || item.id() == null
                        || !allowed.containsKey(item.id()) || !seen.add(item.id())) {
                    continue;
                }
                items.add(new SnapshotItem(item.id(), sanitize(item.reason())));
                llmCount++;
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
        return new MergeResult(items, llmCount);
    }

    private record MergeResult(List<SnapshotItem> items, int llmCount) {
    }

    private record VariantBuild(String buildId, String strategy, String model,
                                String promptVersion, List<SnapshotItem> items, int llmCount) {
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

    private String reasonOf(Throwable ex) {
        if (ex == null) {
            return "UNKNOWN";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
