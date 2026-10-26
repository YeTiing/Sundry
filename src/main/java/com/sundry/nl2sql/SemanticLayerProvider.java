package com.sundry.nl2sql;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载语义层。结构非法时直接启动失败，避免错误口径上线。
 */
@Component
public class SemanticLayerProvider {

    private static final String RESOURCE = "semantic/semantic-layer.yml";

    private final SemanticLayer layer;

    public SemanticLayerProvider() {
        this.layer = load();
    }

    public SemanticLayer layer() {
        return layer;
    }

    private SemanticLayer load() {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = asMap(new Yaml().load(input));
            SemanticLayer.Base base = base(asMap(root.get("base")));

            Map<String, SemanticLayer.Metric> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(root.get("metrics")).entrySet()) {
                Map<String, Object> map = asMap(entry.getValue());
                metrics.put(entry.getKey(), new SemanticLayer.Metric(
                        entry.getKey(),
                        required(map, "label"),
                        strings(map.get("synonyms")),
                        text(map.get("unit")),
                        required(map, "expr"),
                        optionalText(map.get("conditional_join"))));
            }

            Map<String, SemanticLayer.Dimension> dimensions = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(root.get("dimensions")).entrySet()) {
                Map<String, Object> map = asMap(entry.getValue());
                dimensions.put(entry.getKey(), new SemanticLayer.Dimension(
                        entry.getKey(),
                        required(map, "label"),
                        strings(map.get("synonyms")),
                        required(map, "column"),
                        strings(map.get("granularity"))));
            }

            Map<String, SemanticLayer.Filter> filters = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(root.get("filters")).entrySet()) {
                Map<String, Object> map = asMap(entry.getValue());
                filters.put(entry.getKey(), new SemanticLayer.Filter(
                        entry.getKey(),
                        required(map, "label"),
                        required(map, "column"),
                        required(map, "type"),
                        strings(map.get("values"))));
            }

            Map<String, String> conditionalJoins = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(root.get("conditional_joins")).entrySet()) {
                conditionalJoins.put(entry.getKey(), text(entry.getValue()));
            }

            Map<String, Object> limitMap = asMap(root.get("limits"));
            SemanticLayer.Limits limits = new SemanticLayer.Limits(
                    intValue(limitMap.get("max_metrics"), 3),
                    intValue(limitMap.get("max_dimensions"), 3),
                    intValue(limitMap.get("max_range_days"), 365),
                    intValue(limitMap.get("max_rows"), 1000));

            if (metrics.isEmpty() || dimensions.isEmpty() || filters.isEmpty()) {
                throw new IllegalStateException("semantic-layer.yml metrics/dimensions/filters 不能为空");
            }

            return new SemanticLayer(intValue(root.get("version"), 1), base, metrics,
                    dimensions, filters, conditionalJoins, strings(root.get("time_presets")), limits);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 semantic-layer.yml 失败", ex);
        }
    }

    private SemanticLayer.Base base(Map<String, Object> map) {
        List<SemanticLayer.Join> joins = array(map.get("joins")).stream()
                .map(item -> {
                    Map<String, Object> join = asMap(item);
                    return new SemanticLayer.Join(
                            required(join, "table"),
                            required(join, "alias"),
                            required(join, "on"));
                })
                .toList();
        return new SemanticLayer.Base(
                required(map, "from_table"),
                required(map, "from_alias"),
                required(map, "time_column"),
                joins,
                strings(map.get("where")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private List<String> strings(Object value) {
        return array(value).stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }

    private String required(Map<String, Object> map, String key) {
        String value = text(map.get(key));
        if (value.isBlank()) {
            throw new IllegalStateException("semantic-layer.yml 缺少必填字段: " + key);
        }
        return value;
    }

    private String optionalText(Object value) {
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
