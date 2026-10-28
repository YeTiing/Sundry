package com.sundry.nl2sql;

import com.sundry.nl2sql.ValidatedPlan.Dimension;
import com.sundry.nl2sql.ValidatedPlan.FilterBinding;
import com.sundry.nl2sql.ValidatedPlan.Metric;
import com.sundry.nl2sql.ValidatedPlan.SortBinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 只拼 SELECT：指标/维度/筛选全部来自语义层白名单，值全部参数化。
 */
@Component
public class Nl2sqlQueryBuilder {

    private final SemanticLayerProvider provider;

    public Nl2sqlQueryBuilder(SemanticLayerProvider provider) {
        this.provider = provider;
    }

    public QuerySpec build(ValidatedPlan plan) {
        SemanticLayer layer = provider.layer();
        List<String> selects = new ArrayList<>();
        for (Dimension dimension : plan.dimensions()) {
            selects.add(dimension.expression() + " AS " + dimension.key());
        }
        for (Metric metric : plan.metrics()) {
            selects.add(metric.expr() + " AS " + metric.key());
        }

        boolean needsItems = plan.metrics().stream().anyMatch(Metric::itemLevel)
                || plan.dimensions().stream().anyMatch(Dimension::itemLevel)
                || plan.filters().stream().anyMatch(filter -> {
                    SemanticLayer.Filter definition = layer.filters().get(filter.key());
                    return definition != null && definition.itemLevel();
                });

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selects))
                .append(" FROM ")
                .append(layer.base().fromTable())
                .append(' ')
                .append(layer.base().fromAlias());
        for (SemanticLayer.Join join : layer.base().joins()) {
            if (join.itemLevel() && !needsItems) {
                continue;
            }
            sql.append(" JOIN ").append(join.table())
                    .append(' ').append(join.alias())
                    .append(" ON ").append(join.on());
        }
        for (String key : plan.conditionalJoins()) {
            sql.append(' ').append(layer.conditionalJoins().get(key));
        }

        List<Object> params = new ArrayList<>();
        sql.append(" WHERE 1 = 1");
        for (String condition : layer.base().where()) {
            sql.append(" AND ").append(condition);
        }
        sql.append(" AND ").append(layer.base().timeColumn()).append(" >= ?");
        sql.append(" AND ").append(layer.base().timeColumn()).append(" < ?");
        params.add(plan.time().start());
        params.add(plan.time().end());

        for (FilterBinding filter : plan.filters()) {
            sql.append(" AND ").append(filter.column()).append(" = ?");
            params.add(filter.value());
        }

        if (!plan.dimensions().isEmpty()) {
            sql.append(" GROUP BY ")
                    .append(plan.dimensions().stream()
                            .map(Dimension::expression)
                            .collect(Collectors.joining(", ")));
        }

        sql.append(" ORDER BY ")
                .append(plan.sort().stream()
                        .map(sort -> sort.key() + " " + sort.order().toUpperCase())
                        .collect(Collectors.joining(", ")));
        sql.append(" LIMIT ?");
        params.add(plan.limit());

        return new QuerySpec(sql.toString(), List.copyOf(params));
    }
}
