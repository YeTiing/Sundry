package com.sundry.assistant;

import com.sundry.user.Role;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可导航路由目录。加载失败或配置非法时直接启动失败。
 */
@Component
public class RouteCatalog {

    private static final String RESOURCE = "assistant/routes.yml";
    private static final List<String> EXPLAIN_MARKERS =
            List.of("怎么", "如何", "为什么", "能不能", "可以吗", "是什么", "什么意思");
    private static final List<String> NAVIGATION_MARKERS =
            List.of("在哪", "哪里", "打开", "进入", "去", "看看", "看一下", "逛逛", "跳转");
    /**
     * 数据型问题交给模型选工具，避免“最贵的订单”“已发货的订单”被“订单”同义词截成导航。
     * 有明确导航动词时仍以导航为准。
     */
    private static final List<String> DATA_MARKERS = List.of(
            "买过", "买了", "最贵", "最便宜", "多少", "几单", "几次",
            "统计", "排序", "合计", "一共", "总额",
            "销售额", "订单量", "客单价", "退款率", "退款金额", "秒杀单", "GMV", "gmv",
            "去年", "今年", "上个月", "本月", "上周", "本周",
            "近7天", "近30天", "近90天", "最近7天", "最近30天", "最近90天", "最近",
            "已发货", "待发货", "已取消", "已完成", "待支付", "退款");

    private final List<RouteDefinition> routes;

    public RouteCatalog() {
        this.routes = load();
    }

    public List<RouteDefinition> visibleTo(Role role) {
        boolean admin = role == Role.ADMIN || role == Role.SUPER_ADMIN;
        return routes.stream()
                .filter(route -> !route.requiresAdmin() || admin)
                .toList();
    }

    public RouteDefinition findByName(String name, Role role) {
        return visibleTo(role).stream()
                .filter(route -> route.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 规则快路径：长同义词优先，解释型/数据型问题不跳转。
     */
    public RouteDefinition match(String message, Role role) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty() || explanationOnly(text) || dataIntent(text)) {
            return null;
        }
        RouteDefinition best = null;
        int bestLength = -1;
        for (RouteDefinition route : visibleTo(role)) {
            for (String synonym : route.synonyms()) {
                if (synonym != null && !synonym.isBlank()
                        && text.contains(synonym) && synonym.length() > bestLength) {
                    best = route;
                    bestLength = synonym.length();
                }
            }
        }
        return best;
    }

    private boolean explanationOnly(String text) {
        boolean explain = EXPLAIN_MARKERS.stream().anyMatch(text::contains);
        boolean navigate = NAVIGATION_MARKERS.stream().anyMatch(text::contains);
        return explain && !navigate;
    }

    private boolean dataIntent(String text) {
        boolean data = DATA_MARKERS.stream().anyMatch(text::contains);
        boolean navigate = NAVIGATION_MARKERS.stream().anyMatch(text::contains);
        return data && !navigate;
    }

    @SuppressWarnings("unchecked")
    private List<RouteDefinition> load() {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = new Yaml().load(input);
            Object rawRoutes = root == null ? null : root.get("routes");
            if (!(rawRoutes instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("assistant/routes.yml 没有配置 routes");
            }
            List<RouteDefinition> loaded = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (Object item : list) {
                Map<String, Object> map = (Map<String, Object>) item;
                String key = text(map.get("key"));
                String label = text(map.get("label"));
                String name = text(map.get("name"));
                List<String> synonyms = ((List<Object>) map.getOrDefault("synonyms", List.of()))
                        .stream().map(this::text).filter(value -> !value.isBlank()).toList();
                if (key.isBlank() || label.isBlank() || name.isBlank() || synonyms.isEmpty()) {
                    throw new IllegalStateException("assistant/routes.yml 存在不完整路由: " + map);
                }
                if (!keys.add(key) || !names.add(name)) {
                    throw new IllegalStateException("assistant/routes.yml 路由重复: " + key + "/" + name);
                }
                loaded.add(new RouteDefinition(key, label, name, synonyms,
                        Boolean.TRUE.equals(map.get("requiresAuth")),
                        Boolean.TRUE.equals(map.get("requiresAdmin"))));
            }
            return List.copyOf(loaded);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 assistant/routes.yml 失败", ex);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
