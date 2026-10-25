package com.sundry.nl2sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动自检：表/列存在、指标 SQL 能 EXPLAIN。失败直接终止启动。
 */
@Component
public class SemanticLayerValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SemanticLayerValidator.class);
    private static final Pattern COLUMN_REF =
            Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");

    private final SemanticLayerProvider provider;
    private final JdbcTemplate jdbcTemplate;

    public SemanticLayerValidator(SemanticLayerProvider provider, JdbcTemplate jdbcTemplate) {
        this.provider = provider;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate(provider.layer());
    }

    void validate(SemanticLayer layer) {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(layer.base().fromAlias(), layer.base().fromTable());
        layer.base().joins().forEach(join -> aliases.put(join.alias(), join.table()));

        Set<String> tables = new LinkedHashSet<>(aliases.values());
        if (layer.conditionalJoins().values().stream()
                .anyMatch(sql -> sql.contains("order_refund"))) {
            tables.add("order_refund");
        }
        tables.forEach(this::requireTable);

        Set<String> refs = new LinkedHashSet<>();
        collectRefs(refs, String.join(" ", layer.base().where()));
        layer.base().joins().forEach(join -> collectRefs(refs, join.on()));
        layer.metrics().values().forEach(metric -> collectRefs(refs, metric.expr()));
        layer.dimensions().values().forEach(dimension -> collectRefs(refs, dimension.column()));
        layer.filters().values().forEach(filter -> collectRefs(refs, filter.column()));
        for (String ref : refs) {
            int dot = ref.indexOf('.');
            String table = aliases.get(ref.substring(0, dot));
            if (table == null) {
                continue; // 派生表（如 r）不参与基础表列校验
            }
            requireColumn(table, ref.substring(dot + 1));
        }

        layer.metrics().values().forEach(metric -> explain(layer, metric));

        log.info("语义层校验通过 metrics={} dimensions={} filters={}",
                layer.metrics().size(), layer.dimensions().size(), layer.filters().size());
    }

    private void collectRefs(Set<String> refs, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = COLUMN_REF.matcher(text);
        while (matcher.find()) {
            refs.add(matcher.group(1) + "." + matcher.group(2));
        }
    }

    private void requireTable(String table) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Long.class, table);
        if (count == null || count == 0) {
            throw new IllegalStateException("语义层引用了不存在的表: " + table);
        }
    }

    private void requireColumn(String table, String column) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Long.class, table, column);
        if (count == null || count == 0) {
            throw new IllegalStateException("语义层引用了不存在的列: " + table + "." + column);
        }
    }

    private void explain(SemanticLayer layer, SemanticLayer.Metric metric) {
        StringBuilder sql = new StringBuilder("EXPLAIN SELECT ")
                .append(metric.expr())
                .append(" FROM ").append(layer.base().fromTable())
                .append(' ').append(layer.base().fromAlias());
        for (SemanticLayer.Join join : layer.base().joins()) {
            sql.append(" JOIN ").append(join.table()).append(' ').append(join.alias())
                    .append(" ON ").append(join.on());
        }
        if (metric.conditionalJoin() != null) {
            String conditional = layer.conditionalJoins().get(metric.conditionalJoin());
            if (conditional == null) {
                throw new IllegalStateException("指标 " + metric.key()
                        + " 引用了未定义的 conditional_join: " + metric.conditionalJoin());
            }
            sql.append(' ').append(conditional);
        }
        if (!layer.base().where().isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", layer.base().where()));
        }
        try {
            jdbcTemplate.queryForList(sql.toString());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("语义层指标 " + metric.key()
                    + " 的 EXPLAIN 失败: " + sql, ex);
        }
    }
}
