package com.sundry.nl2sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自由 SQL 沙箱：单条 PlainSelect、白名单表/列、禁危险函数、强制 LIMIT。
 * 解析失败或任何越界都直接拒绝，不尝试“修复”。
 */
@Component
public class Nl2sqlFreeSqlGuard {

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "orders", "order_item", "product", "category", "order_refund");

    private static final Map<String, Set<String>> ALLOWED_COLUMNS = Map.of(
            "orders", Set.of("id", "order_no", "activity_id", "order_source", "user_id",
                    "status", "total_amount", "has_physical", "created_at", "paid_at",
                    "packed_at", "shipped_at", "completed_at", "cancelled_at",
                    "refunded_at", "refund_amount"),
            "order_item", Set.of("id", "order_id", "product_id", "product_name",
                    "product_type", "price", "quantity", "cover_url", "digital_file_id"),
            "product", Set.of("id", "category_id", "name", "description", "tags",
                    "type", "price", "stock", "status", "created_at", "updated_at"),
            "category", Set.of("id", "name", "sort_order", "status", "created_at"),
            "order_refund", Set.of("id", "order_id", "user_id", "amount", "kind",
                    "status", "prev_status", "applied_at", "reviewed_at"));

    private static final Set<String> DENIED_FUNCTIONS = Set.of(
            "sleep", "benchmark", "load_file", "get_lock", "release_lock",
            "is_free_lock", "is_used_lock", "master_pos_wait", "sys_exec", "sys_eval",
            "extractvalue", "updatexml", "last_insert_id", "connection_id",
            "current_user", "session_user", "system_user", "database", "version");

    private static final long MAX_OFFSET = 100_000;

    public String validate(String rawSql, int maxRows) {
        String sql = rawSql == null ? "" : rawSql.trim();
        if (sql.isEmpty()) {
            throw invalid("EMPTY_SQL", "SQL 不能为空");
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException ex) {
            throw invalid("PARSE_ERROR", "SQL 无法解析");
        }
        if (statements.size() != 1) {
            throw invalid("MULTI_STATEMENT", "只允许一条 SELECT");
        }

        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            throw invalid("ONLY_SELECT", "只允许 SELECT");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw invalid("WITH_NOT_ALLOWED", "不允许 WITH 子句");
        }
        if (select.getForUpdateTable() != null || select.getForClause() != null
                || select.getForMode() != null || select.getIsolation() != null) {
            throw invalid("LOCK_NOT_ALLOWED", "不允许锁或隔离级别子句");
        }
        if (select.getLimitBy() != null) {
            throw invalid("INVALID_LIMIT", "不支持 LIMIT BY");
        }

        if (!(select instanceof PlainSelect plain)) {
            throw invalid("ONLY_PLAIN_SELECT", "不允许 UNION / INTERSECT / EXCEPT / 子查询");
        }
        if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()
                || plain.getIntoTempTable() != null) {
            throw invalid("INTO_NOT_ALLOWED", "不允许 SELECT INTO");
        }
        if (plain.getTop() != null || plain.getFetch() != null
                || plain.getFirst() != null || plain.getSkip() != null) {
            throw invalid("INVALID_LIMIT", "只支持 MySQL LIMIT");
        }
        rejectSelectAll(plain);

        GuardedFinder finder = new GuardedFinder();
        Set<String> tables = finder.getTables(statement);
        if (finder.subquery) {
            throw invalid("SUBQUERY_NOT_ALLOWED", "不允许子查询");
        }
        if (tables.isEmpty()) {
            throw invalid("TABLE_REQUIRED", "必须指定表");
        }
        for (String table : tables) {
            checkTable(table);
        }

        Map<String, String> aliases = aliases(plain);
        for (Column column : finder.columns) {
            checkColumn(column, aliases);
        }
        for (Join join : joins(plain)) {
            for (Column column : join.getUsingColumns()) {
                checkColumn(column, aliases);
            }
        }
        for (String function : finder.functions) {
            if (function.contains(".") || DENIED_FUNCTIONS.contains(function)) {
                throw invalid("FUNCTION_NOT_ALLOWED", "不允许函数: " + function);
            }
        }

        enforceLimit(plain, maxRows);
        return plain.toString();
    }

    private void rejectSelectAll(PlainSelect plain) {
        for (SelectItem<?> item : plain.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns) {
                throw invalid("STAR_NOT_ALLOWED", "不允许 SELECT *，必须写明列");
            }
        }
    }

    private void enforceLimit(PlainSelect plain, int maxRows) {
        Limit limit = plain.getLimit();
        if (limit == null) {
            plain.setLimit(new Limit().withRowCount(new LongValue(maxRows)));
            return;
        }
        if (limit.isLimitAll()) {
            limit.setLimitAll(false);
            limit.setRowCount(new LongValue(maxRows));
        }
        if (!(limit.getRowCount() instanceof LongValue rowCount)) {
            throw invalid("INVALID_LIMIT", "LIMIT 必须是数字");
        }
        if (rowCount.getValue() < 1 || rowCount.getValue() > maxRows) {
            throw invalid("INVALID_LIMIT", "LIMIT 必须在 1~" + maxRows + " 之间");
        }
        if (limit.getOffset() != null) {
            checkOffset(limit.getOffset());
        }
        if (plain.getOffset() != null) {
            checkOffset(plain.getOffset().getOffset());
        }
    }

    private void checkOffset(net.sf.jsqlparser.expression.Expression expression) {
        if (!(expression instanceof LongValue offset)) {
            throw invalid("INVALID_LIMIT", "OFFSET 必须是数字");
        }
        if (offset.getValue() < 0 || offset.getValue() > MAX_OFFSET) {
            throw invalid("INVALID_LIMIT", "OFFSET 过大");
        }
    }

    private void checkTable(String fullName) {
        String lower = fullName.toLowerCase(Locale.ROOT);
        String base = lower;
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            if (!"sundry".equals(lower.substring(0, dot))) {
                throw invalid("TABLE_NOT_ALLOWED", "不允许访问该表: " + fullName);
            }
            base = lower.substring(dot + 1);
        }
        if (!ALLOWED_TABLES.contains(base)) {
            throw invalid("TABLE_NOT_ALLOWED", "不允许访问该表: " + fullName);
        }
    }

    private void checkColumn(Column column, Map<String, String> aliases) {
        String columnName = column.getColumnName() == null
                ? "" : column.getColumnName().toLowerCase(Locale.ROOT);
        String tableRef = column.getTableName();
        String table;
        if (tableRef == null || tableRef.isBlank()) {
            Set<String> distinct = new LinkedHashSet<>(aliases.values());
            if (distinct.size() != 1) {
                throw invalid("AMBIGUOUS_COLUMN", "多表查询必须写清列所属的表: " + columnName);
            }
            table = distinct.iterator().next();
        } else {
            String ref = tableRef.toLowerCase(Locale.ROOT);
            int dot = ref.lastIndexOf('.');
            if (dot >= 0) {
                if (!"sundry".equals(ref.substring(0, dot))) {
                    throw invalid("TABLE_NOT_ALLOWED", "不允许访问该表: " + tableRef);
                }
                ref = ref.substring(dot + 1);
            }
            table = aliases.get(ref);
            if (table == null) {
                throw invalid("UNKNOWN_TABLE", "未知的表或别名: " + tableRef);
            }
        }
        if (!ALLOWED_COLUMNS.getOrDefault(table, Set.of()).contains(columnName)) {
            throw invalid("COLUMN_NOT_ALLOWED", "不允许查询列: " + table + "." + columnName);
        }
    }

    private Map<String, String> aliases(PlainSelect plain) {
        Map<String, String> aliases = new HashMap<>();
        FromItem from = plain.getFromItem();
        if (!(from instanceof Table table)) {
            throw invalid("SUBQUERY_NOT_ALLOWED", "不允许子查询");
        }
        addAlias(aliases, table);
        for (Join join : joins(plain)) {
            FromItem right = join.getRightItem();
            if (right == null) {
                right = join.getFromItem();
            }
            if (!(right instanceof Table joinTable)) {
                throw invalid("SUBQUERY_NOT_ALLOWED", "不允许子查询");
            }
            addAlias(aliases, joinTable);
        }
        return aliases;
    }

    private List<Join> joins(PlainSelect plain) {
        return plain.getJoins() == null ? List.of() : plain.getJoins();
    }

    private void addAlias(Map<String, String> aliases, Table table) {
        String full = table.getFullyQualifiedName().toLowerCase(Locale.ROOT);
        int dot = full.lastIndexOf('.');
        String base = dot >= 0 ? full.substring(dot + 1) : full;
        aliases.put(base, base);
        if (table.getAlias() != null && table.getAlias().getName() != null
                && !table.getAlias().getName().isBlank()) {
            aliases.put(table.getAlias().getName().toLowerCase(Locale.ROOT), base);
        }
    }

    private Nl2sqlValidationException invalid(String code, String message) {
        return new Nl2sqlValidationException(code, message);
    }

    /**
     * 只额外收集列、函数和子查询标记；表名沿用 TablesNamesFinder。
     */
    private static final class GuardedFinder extends TablesNamesFinder<Void> {

        private final List<Column> columns = new ArrayList<>();
        private final Set<String> functions = new HashSet<>();
        private boolean subquery;

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            if (function.getName() != null) {
                functions.add(function.getName().toLowerCase(Locale.ROOT));
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            subquery = true;
            return null;
        }
    }
}
