package com.sundry.nl2sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只执行 SELECT；有独立只读数据源就用它，否则开发环境回退到主数据源。
 */
@Component
public class Nl2sqlQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(Nl2sqlQueryExecutor.class);

    private final JdbcTemplate primary;
    private final Nl2sqlProperties properties;
    private volatile JdbcTemplate readOnly;
    private volatile HikariDataSource readOnlyDataSource;
    private volatile JdbcTemplate freeReadOnly;
    private volatile HikariDataSource freeReadOnlyDataSource;
    private volatile boolean fallbackLogged;

    public Nl2sqlQueryExecutor(JdbcTemplate primary, Nl2sqlProperties properties) {
        this.primary = primary;
        this.properties = properties;
    }

    public List<Map<String, Object>> execute(QuerySpec spec, int timeoutSeconds, int maxRows) {
        return run(template(), spec, timeoutSeconds, maxRows).rows();
    }

    public Result executeFree(QuerySpec spec, int timeoutSeconds, int maxRows) {
        return run(freeTemplate(), spec, timeoutSeconds, maxRows);
    }

    private Result run(JdbcTemplate template, QuerySpec spec,
                       int timeoutSeconds, int maxRows) {
        String sql = spec.sql() == null ? "" : spec.sql().trim();
        if (!sql.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new Nl2sqlException("ONLY_SELECT");
        }
        return template.execute((ConnectionCallback<Result>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(Math.max(1, timeoutSeconds));
                statement.setMaxRows(Math.max(1, maxRows));
                for (int i = 0; i < spec.params().size(); i++) {
                    statement.setObject(i + 1, spec.params().get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns.get(i - 1), normalize(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    return new Result(List.copyOf(columns), rows);
                }
            }
        });
    }

    private Object normalize(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        return value;
    }

    private JdbcTemplate template() {
        String url = properties.getDatasource().getUrl();
        if (url == null || url.isBlank()) {
            if (!fallbackLogged) {
                log.warn("未配置 NL2SQL 只读数据源，使用主数据源执行查询");
                fallbackLogged = true;
            }
            return primary;
        }
        if (readOnly == null) {
            synchronized (this) {
                if (readOnly == null) {
                    readOnly = buildReadOnly(url);
                }
            }
        }
        return readOnly;
    }

    private JdbcTemplate buildReadOnly(String url) {
        readOnlyDataSource = buildPool(properties.getDatasource(), "nl2sql-readonly");
        return new JdbcTemplate(readOnlyDataSource);
    }

    private JdbcTemplate freeTemplate() {
        String url = properties.getFreeDatasource().getUrl();
        if (url == null || url.isBlank()) {
            throw new Nl2sqlException("FREE_SQL_DATASOURCE_MISSING");
        }
        if (freeReadOnly == null) {
            synchronized (this) {
                if (freeReadOnly == null) {
                    freeReadOnlyDataSource = buildPool(properties.getFreeDatasource(),
                            "nl2sql-free-readonly");
                    freeReadOnly = new JdbcTemplate(freeReadOnlyDataSource);
                }
            }
        }
        return freeReadOnly;
    }

    private HikariDataSource buildPool(Nl2sqlProperties.Datasource datasource, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(datasource.getUrl());
        config.setUsername(datasource.getUsername());
        config.setPassword(datasource.getPassword());
        config.setMaximumPoolSize(Math.max(1, datasource.getMaximumPoolSize()));
        config.setReadOnly(true);
        config.setPoolName(name);
        return new HikariDataSource(config);
    }

    @PreDestroy
    void close() {
        if (readOnlyDataSource != null) {
            readOnlyDataSource.close();
        }
        if (freeReadOnlyDataSource != null) {
            freeReadOnlyDataSource.close();
        }
    }

    public record Result(List<String> columns, List<Map<String, Object>> rows) {
    }
}
