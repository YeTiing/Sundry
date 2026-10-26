package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.nl2sql.Nl2sqlProperties;
import com.sundry.nl2sql.Nl2sqlService;
import com.sundry.nl2sql.dto.Nl2sqlQueryRequest;
import com.sundry.nl2sql.dto.Nl2sqlQueryView;
import com.sundry.user.Role;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员经营分析。助手只负责把问题带过来，权限和 SQL 都在 NL2SQL 链路里。
 */
@Component
public class QuerySalesTool implements AssistantTool {

    private final Nl2sqlService nl2sqlService;
    private final Nl2sqlProperties properties;

    public QuerySalesTool(Nl2sqlService nl2sqlService, Nl2sqlProperties properties) {
        this.nl2sqlService = nl2sqlService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "query_sales";
    }

    @Override
    public String description() {
        return "查询经营数据，例如销售额、订单量、客单价、退款率、秒杀单量。"
                + "只在管理员问经营分析问题时使用。";
    }

    @Override
    public Set<Role> roles() {
        return Set.of(Role.ADMIN, Role.SUPER_ADMIN);
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "minLength", 1, "maxLength", 200)),
                "required", List.of("question"),
                "additionalProperties", false);
    }

    @Override
    public AssistantToolResult execute(AssistantToolContext context, JsonNode args) {
        JsonNode node = args.get("question");
        if (node == null || !node.isTextual()) {
            throw new AssistantToolException("INVALID_ARGS", "请提供要分析的问题。");
        }
        String question = node.asText().trim();
        if (question.isEmpty() || question.length() > 200) {
            throw new AssistantToolException("INVALID_ARGS", "问题长度需要在 1~200 字之间。");
        }
        if (!properties.isEnabled()) {
            throw new AssistantToolException("NL2SQL_DISABLED", "经营分析暂未开放。");
        }
        try {
            Nl2sqlQueryView view = nl2sqlService.query(
                    new AuthenticatedUser(context.userId(), context.role()),
                    new Nl2sqlQueryRequest(question, null));
            return new AssistantToolResult(format(view));
        } catch (BusinessException ex) {
            String message = switch (ex.getErrorCode()) {
                case NL2SQL_DISABLED -> "经营分析暂未开放。";
                case NL2SQL_UNSUPPORTED -> "这个问题暂时无法分析：" + ex.getMessage();
                case NL2SQL_PLAN_REJECTED -> "查询计划被拒绝：" + ex.getMessage();
                case RATE_LIMITED -> "问得太快，稍后再试。";
                default -> "经营分析暂时没有完成，请稍后再试。";
            };
            throw new AssistantToolException(ex.getErrorCode().name(), message);
        }
    }

    private String format(Nl2sqlQueryView view) {
        if (view.rows().isEmpty()) {
            return view.answer();
        }
        if (view.rows().size() == 1 && view.columns().size() == 1) {
            return view.answer();
        }
        StringBuilder reply = new StringBuilder(view.answer());
        reply.append('\n');
        int limit = Math.min(5, view.rows().size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = view.rows().get(i);
            List<String> parts = new ArrayList<>();
            for (Nl2sqlQueryView.Column column : view.columns()) {
                String unit = column.unit() == null || column.unit().isBlank()
                        ? "" : " " + column.unit();
                parts.add(column.label() + "：" + display(row.get(column.key())) + unit);
            }
            reply.append(String.join("，", parts)).append('\n');
        }
        if (view.rows().size() > limit) {
            reply.append("……共 ").append(view.rowCount()).append(" 行");
        }
        String text = reply.toString().trim();
        return text.length() > 800 ? text.substring(0, 800) + "…" : text;
    }

    private String display(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
