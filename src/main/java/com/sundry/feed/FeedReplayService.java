package com.sundry.feed;

import com.sundry.feed.dto.FeedReplayView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线回放：读取候选快照和构建后的点击，在同一候选集上比较规则顺序与 AI 顺序。
 * 不调用模型，不修改线上数据。
 */
@Service
public class FeedReplayService {

    private static final Logger log = LoggerFactory.getLogger(FeedReplayService.class);
    private static final int MAX_SNAPSHOTS = 2000;
    private static final int BUILD_ID_CHUNK = 500;
    private static final int MAX_K = 50;
    private static final String RULE_FALLBACK_MODEL = "rule-fallback";
    private static final double LN2 = Math.log(2);

    private final FeedMetricsMapper mapper;
    private final ObjectMapper objectMapper;

    public FeedReplayService(FeedMetricsMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public FeedReplayView replay(int days, int k) {
        int window = Math.min(Math.max(days, 1), 90);
        int topK = Math.min(Math.max(k, 1), MAX_K);
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        List<FeedMetricsMapper.ReplaySnapshotRow> rows =
                mapper.findReplaySnapshots(since, MAX_SNAPSHOTS);
        List<Parsed> parsed = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (FeedMetricsMapper.ReplaySnapshotRow row : rows) {
            Parsed snapshot = parse(row);
            if (snapshot == null) {
                continue;
            }
            parsed.add(snapshot);
            for (VariantRef variant : snapshot.variants()) {
                if (RULE_FALLBACK_MODEL.equals(variant.model()) || variant.buildId().isBlank()) {
                    continue;
                }
                targets.put(variant.buildId(), new Target(variant.strategy(),
                        variant.items(), snapshot.candidates(), snapshot.candidateIds()));
            }
        }

        Map<String, List<Long>> clicks = loadClicks(since, targets.keySet());
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        Aggregate all = new Aggregate();
        int samples = 0;
        long clickedTotal = 0;
        long clickedInCandidates = 0;

        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            List<Long> clickedIds = clicks.get(entry.getKey());
            if (clickedIds == null || clickedIds.isEmpty()) {
                continue;
            }
            Target target = entry.getValue();
            Set<Long> distinctClicks = new LinkedHashSet<>(clickedIds);
            if (distinctClicks.isEmpty()) {
                continue;
            }
            List<Long> ruleTop = ruleTopK(target.candidates(), target.candidateIds(), topK);
            List<Long> aiTop = aiTopK(target.items(), target.candidateIds(), topK);
            if (ruleTop.isEmpty() || aiTop.isEmpty()) {
                continue;
            }
            long lastClick = clickedIds.get(clickedIds.size() - 1);
            double ruleRecall = recall(ruleTop, distinctClicks);
            double aiRecall = recall(aiTop, distinctClicks);
            double ruleNdcg = ndcg(ruleTop, distinctClicks);
            double aiNdcg = ndcg(aiTop, distinctClicks);
            double ruleLast = ruleTop.contains(lastClick) ? 1.0 : 0.0;
            double aiLast = aiTop.contains(lastClick) ? 1.0 : 0.0;

            aggregates.computeIfAbsent(target.strategy(), ignored -> new Aggregate())
                    .add(ruleRecall, aiRecall, ruleNdcg, aiNdcg, ruleLast, aiLast);
            all.add(ruleRecall, aiRecall, ruleNdcg, aiNdcg, ruleLast, aiLast);
            samples++;

            clickedTotal += distinctClicks.size();
            long inCandidates = distinctClicks.stream()
                    .filter(target.candidateIds()::contains).count();
            clickedInCandidates += inCandidates;
        }

        List<FeedReplayView.StrategyReplay> byStrategy = new ArrayList<>();
        for (String strategy : List.of("PREF", "EXPLORE")) {
            Aggregate aggregate = aggregates.get(strategy);
            if (aggregate != null && aggregate.samples > 0) {
                byStrategy.add(aggregate.view(strategy));
            }
        }
        if (all.samples > 0) {
            byStrategy.add(all.view("ALL"));
        }
        double coverage = clickedTotal == 0 ? 0 : round(clickedInCandidates / (double) clickedTotal);
        return new FeedReplayView(window, topK, parsed.size(), samples, coverage, byStrategy);
    }

    private Map<String, List<Long>> loadClicks(LocalDateTime since, Set<String> buildIds) {
        Map<String, List<Long>> clicks = new HashMap<>();
        List<String> ids = new ArrayList<>(buildIds);
        for (int start = 0; start < ids.size(); start += BUILD_ID_CHUNK) {
            List<String> chunk = ids.subList(start, Math.min(ids.size(), start + BUILD_ID_CHUNK));
            for (FeedMetricsMapper.ReplayClickRow row : mapper.findReplayClicks(chunk, since)) {
                if (row.getBuildId() == null || row.getProductId() == null) {
                    continue;
                }
                clicks.computeIfAbsent(row.getBuildId(), ignored -> new ArrayList<>())
                        .add(row.getProductId());
            }
        }
        return clicks;
    }

    private Parsed parse(FeedMetricsMapper.ReplaySnapshotRow row) {
        try {
            JsonNode root = objectMapper.readTree(row.getCandidateSnapshot());
            List<CandidateRef> candidates = new ArrayList<>();
            Map<Long, Integer> ids = new LinkedHashMap<>();
            JsonNode candidateNodes = root.path("candidates");
            for (int i = 0; i < candidateNodes.size(); i++) {
                JsonNode node = candidateNodes.get(i);
                long id = node.path("id").asLong(0);
                if (id <= 0) {
                    continue;
                }
                int rank = node.path("ruleRank").asInt(i + 1);
                if (rank <= 0) {
                    rank = i + 1;
                }
                candidates.add(new CandidateRef(id, rank));
                ids.put(id, rank);
            }
            List<VariantRef> variants = new ArrayList<>();
            JsonNode variantNodes = root.path("variants");
            for (int i = 0; i < variantNodes.size(); i++) {
                JsonNode node = variantNodes.get(i);
                List<Long> items = new ArrayList<>();
                JsonNode itemNodes = node.path("items");
                for (int j = 0; j < itemNodes.size(); j++) {
                    long id = itemNodes.get(j).asLong(0);
                    if (id > 0) {
                        items.add(id);
                    }
                }
                variants.add(new VariantRef(node.path("buildId").asText(""),
                        node.path("strategy").asText("UNKNOWN"),
                        node.path("model").asText(""), items));
            }
            candidates.sort(Comparator.comparingInt(CandidateRef::rank));
            return new Parsed(candidates, new LinkedHashSet<>(ids.keySet()), variants);
        } catch (RuntimeException ex) {
            log.warn("候选快照解析失败 logId={}", row.getId(), ex);
            return null;
        }
    }

    private List<Long> ruleTopK(List<CandidateRef> candidates, Set<Long> allowed, int k) {
        List<Long> result = new ArrayList<>();
        for (CandidateRef candidate : candidates) {
            if (allowed.contains(candidate.id())) {
                result.add(candidate.id());
                if (result.size() >= k) {
                    break;
                }
            }
        }
        return result;
    }

    private List<Long> aiTopK(List<Long> items, Set<Long> allowed, int k) {
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : items) {
            if (allowed.contains(id)) {
                result.add(id);
                if (result.size() >= k) {
                    break;
                }
            }
        }
        return new ArrayList<>(result);
    }

    private double recall(List<Long> topK, Set<Long> clicks) {
        if (clicks.isEmpty()) {
            return 0;
        }
        long hits = clicks.stream().filter(topK::contains).count();
        return hits / (double) clicks.size();
    }

    private double ndcg(List<Long> topK, Set<Long> clicks) {
        if (clicks.isEmpty()) {
            return 0;
        }
        double dcg = 0;
        for (int i = 0; i < topK.size(); i++) {
            if (clicks.contains(topK.get(i))) {
                dcg += 1.0 / log2(i + 2);
            }
        }
        double idcg = 0;
        int ideal = Math.min(clicks.size(), topK.size());
        for (int i = 1; i <= ideal; i++) {
            idcg += 1.0 / log2(i + 1);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / LN2;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record CandidateRef(long id, int rank) {
    }

    private record VariantRef(String buildId, String strategy, String model, List<Long> items) {
    }

    private record Parsed(List<CandidateRef> candidates, Set<Long> candidateIds,
                          List<VariantRef> variants) {
    }

    private record Target(String strategy, List<Long> items, List<CandidateRef> candidates,
                          Set<Long> candidateIds) {
    }

    private static final class Aggregate {
        private int samples;
        private double ruleRecall;
        private double aiRecall;
        private double ruleNdcg;
        private double aiNdcg;
        private double ruleLast;
        private double aiLast;

        void add(double ruleRecall, double aiRecall, double ruleNdcg, double aiNdcg,
                 double ruleLast, double aiLast) {
            samples++;
            this.ruleRecall += ruleRecall;
            this.aiRecall += aiRecall;
            this.ruleNdcg += ruleNdcg;
            this.aiNdcg += aiNdcg;
            this.ruleLast += ruleLast;
            this.aiLast += aiLast;
        }

        FeedReplayView.StrategyReplay view(String strategy) {
            return new FeedReplayView.StrategyReplay(strategy, samples,
                    new FeedReplayView.Side(round(ruleRecall / samples),
                            round(ruleNdcg / samples), round(ruleLast / samples)),
                    new FeedReplayView.Side(round(aiRecall / samples),
                            round(aiNdcg / samples), round(aiLast / samples)));
        }

        private double round(double value) {
            return Math.round(value * 10_000.0) / 10_000.0;
        }
    }
}
