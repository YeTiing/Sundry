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
    private volatile boolean fallbackLogged;

    public Nl2sqlQueryExecutor(JdbcTemplate primary, Nl2sqlProperties properties) {
        this.primary = primary;
        this.properties = properties;
    }

    public List<Map<String, Object>> execute(QuerySpec spec, int timeoutSeconds, int maxRows) {
        String sql = spec.sql() == null ? "" : spec.sql().trim();
        if (!sql.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new Nl2sqlException("ONLY_SELECT");
        }
        return template().execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(Math.max(1, timeoutSeconds));
                statement.setMaxRows(Math.max(1, maxRows));
                for (int i = 0; i < spec.params().size(); i++) {
                    statement.setObject(i + 1, spec.params().get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    return rows;
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
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(properties.getDatasource().getUsername());
        config.setPassword(properties.getDatasource().getPassword());
        config.setMaximumPoolSize(Math.max(1, properties.getDatasource().getMaximumPoolSize()));
        config.setReadOnly(true);
        config.setPoolName("nl2sql-readonly");
        readOnlyDataSource = new HikariDataSource(config);
        return new JdbcTemplate(readOnlyDataSource);
    }

    @PreDestroy
    void close() {
        if (readOnlyDataSource != null) {
            readOnlyDataSource.close();
        }
    }
}
