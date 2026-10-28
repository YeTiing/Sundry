package com.sundry.nl2sql;

import com.sundry.nl2sql.ValidatedPlan.Dimension;
import com.sundry.nl2sql.ValidatedPlan.FilterBinding;
import com.sundry.nl2sql.ValidatedPlan.Metric;
import com.sundry.nl2sql.ValidatedPlan.SortBinding;
import com.sundry.nl2sql.ValidatedPlan.TimeRange;
import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 计划只认语义层里的 key；任何不在白名单里的东西都不会进 SQL。
 */
@Component
public class Nl2sqlPlanValidator {

    private static final List<String> GRANULARITIES = List.of("day", "week", "month");

    private final SemanticLayerProvider provider;
    private final Nl2sqlTimeRangeResolver timeRangeResolver;

    public Nl2sqlPlanValidator(SemanticLayerProvider provider,
                               Nl2sqlTimeRangeResolver timeRangeResolver) {
        this.provider = provider;
        this.timeRangeResolver = timeRangeResolver;
    }

    public ValidatedPlan validate(Nl2sqlPlanResponse.Plan plan) {
        if (plan == null) {
            throw invalid("EMPTY_PLAN", "没有生成查询计划");
        }
        SemanticLayer layer = provider.layer();
        List<Metric> metrics = metrics(plan, layer);
        List<Dimension> dimensions = dimensions(plan, layer);
        checkCompatibility(metrics, dimensions, layer);
        List<FilterBinding> filters = filters(plan, layer);
        TimeRange time = time(plan, layer);
        List<SortBinding> sort = sort(plan, metrics);
        int limit = limit(plan, layer);
        List<String> conditionalJoins = metrics.stream()
                .map(metric -> layer.metrics().get(metric.key()).conditionalJoin())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (String join : conditionalJoins) {
            if (!layer.conditionalJoins().containsKey(join)) {
                throw invalid("INVALID_JOIN", "指标依赖的 JOIN 未定义");
            }
        }
        return new ValidatedPlan(metrics, dimensions, filters, time, sort, limit,
                conditionalJoins);
    }

    private List<Metric> metrics(Nl2sqlPlanResponse.Plan plan, SemanticLayer layer) {
        if (plan.metrics() == null || plan.metrics().isEmpty()) {
            throw invalid("EMPTY_METRICS", "至少需要一个指标");
        }
        if (plan.metrics().size() > layer.limits().maxMetrics()) {
            throw invalid("TOO_MANY_METRICS", "指标数量过多");
        }
        List<Metric> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (String key : plan.metrics()) {
            if (!keys.add(key)) {
                throw invalid("DUPLICATE_METRIC", "指标重复");
            }
            SemanticLayer.Metric metric = layer.metrics().get(key);
            if (metric == null) {
                throw invalid("UNSUPPORTED_METRIC", "不支持的指标: " + key);
            }
            result.add(new Metric(metric.key(), metric.label(), metric.unit(), metric.expr()));
        }
        return result;
    }

    private List<Dimension> dimensions(Nl2sqlPlanResponse.Plan plan, SemanticLayer layer) {
        List<String> keys = plan.dimensions() == null ? List.of() : plan.dimensions();
        if (keys.size() > layer.limits().maxDimensions()) {
            throw invalid("TOO_MANY_DIMENSIONS", "维度数量过多");
        }
        String granularity = plan.granularity() == null || plan.granularity().isBlank()
                ? "day" : plan.granularity();
        if (!GRANULARITIES.contains(granularity)) {
            throw invalid("INVALID_GRANULARITY", "不支持的粒度");
        }
        List<Dimension> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw invalid("DUPLICATE_DIMENSION", "维度重复");
            }
            SemanticLayer.Dimension dimension = layer.dimensions().get(key);
            if (dimension == null) {
                throw invalid("UNSUPPORTED_DIMENSION", "不支持的维度: " + key);
            }
            result.add(new Dimension(key, dimension.label(),
                    expression(key, dimension, granularity, layer.base().timeColumn())));
        }
        return result;
    }

    private String expression(String key, SemanticLayer.Dimension dimension,
                              String granularity, String timeColumn) {
        if (!"day".equals(key)) {
            return dimension.column();
        }
        return switch (granularity) {
            case "week" -> "DATE_FORMAT(" + timeColumn + ", '%x-W%v')";
            case "month" -> "DATE_FORMAT(" + timeColumn + ", '%Y-%m')";
            default -> dimension.column();
        };
    }

    private void checkCompatibility(List<Metric> metrics, List<Dimension> dimensions,
                                    SemanticLayer layer) {
        boolean refundMetric = metrics.stream()
                .anyMatch(metric -> layer.metrics().get(metric.key()).conditionalJoin() != null);
        boolean itemLevelDimension = dimensions.stream()
                .anyMatch(dimension -> "category".equals(dimension.key())
                        || "product".equals(dimension.key())
                        || "product_type".equals(dimension.key()));
        if (refundMetric && itemLevelDimension) {
            throw invalid("INCOMPATIBLE_PAIR", "退款指标不能按商品/分类维度拆分");
        }
    }

    private List<FilterBinding> filters(Nl2sqlPlanResponse.Plan plan, SemanticLayer layer) {
        List<Nl2sqlPlanResponse.Plan.Filter> filters = plan.filters() == null
                ? List.of() : plan.filters();
        List<FilterBinding> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Nl2sqlPlanResponse.Plan.Filter filter : filters) {
            if (filter == null || filter.key() == null || !keys.add(filter.key())) {
                throw invalid("DUPLICATE_FILTER", "筛选条件重复或为空");
            }
            SemanticLayer.Filter definition = layer.filters().get(filter.key());
            if (definition == null) {
                throw invalid("UNSUPPORTED_FILTER", "不支持的筛选: " + filter.key());
            }
            if ("time".equals(definition.type())) {
                throw invalid("UNSUPPORTED_FILTER", "时间范围请使用 time.preset");
            }
            Object value = filter.value();
            if ("enum".equals(definition.type())) {
                String text = value == null ? "" : String.valueOf(value);
                if (!definition.values().contains(text)) {
                    throw invalid("INVALID_ENUM_VALUE", "筛选值不合法: " + text);
                }
                result.add(new FilterBinding(definition.key(), definition.column(), text));
            } else if ("id".equals(definition.type())) {
                long parsed;
                try {
                    parsed = Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ex) {
                    throw invalid("INVALID_FILTER", "筛选值必须是数字");
                }
                if (parsed <= 0) {
                    throw invalid("INVALID_FILTER", "筛选值必须大于 0");
                }
                result.add(new FilterBinding(definition.key(), definition.column(), parsed));
            } else {
                throw invalid("UNSUPPORTED_FILTER", "不支持的筛选类型");
            }
        }
        return result;
    }

    private TimeRange time(Nl2sqlPlanResponse.Plan plan, SemanticLayer layer) {
        String preset = plan.time() == null ? null : plan.time().preset();
        TimeRange range;
        try {
            range = timeRangeResolver.resolve(preset);
        } catch (Nl2sqlValidationException ex) {
            throw ex;
        }
        if (Duration.between(range.start(), range.end()).toDays() > layer.limits().maxRangeDays()) {
            throw invalid("INVALID_TIME_RANGE", "时间范围过大");
        }
        return range;
    }

    private List<SortBinding> sort(Nl2sqlPlanResponse.Plan plan, List<Metric> metrics) {
        List<Nl2sqlPlanResponse.Plan.Sort> sorts = plan.sort() == null ? List.of() : plan.sort();
        List<SortBinding> result = new ArrayList<>();
        Set<String> metricKeys = new LinkedHashSet<>();
        metrics.forEach(metric -> metricKeys.add(metric.key()));
        for (Nl2sqlPlanResponse.Plan.Sort sort : sorts) {
            if (sort == null || !metricKeys.contains(sort.metric())) {
                throw invalid("INVALID_SORT", "只能按已选指标排序");
            }
            String order = sort.order() == null ? "desc" : sort.order().toLowerCase();
            if (!"asc".equals(order) && !"desc".equals(order)) {
                throw invalid("INVALID_SORT", "排序方向不合法");
            }
            result.add(new SortBinding(sort.metric(), order));
        }
        if (result.isEmpty()) {
            result.add(new SortBinding(metrics.get(0).key(), "desc"));
        }
        return result;
    }

    private int limit(Nl2sqlPlanResponse.Plan plan, SemanticLayer layer) {
        int limit = plan.limit() == null ? 10 : plan.limit();
        if (limit < 1 || limit > layer.limits().maxRows()) {
            throw invalid("INVALID_LIMIT", "返回行数超出限制");
        }
        return limit;
    }

    private Nl2sqlValidationException invalid(String code, String message) {
        return new Nl2sqlValidationException(code, message);
    }
}
