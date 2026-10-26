package com.sundry.assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 助手订单类工具共用的时间预设；模型只选 preset，绝对时间在 Java 换算。
 */
enum OrderTimeRange {
    LAST_7D("近 7 天"),
    LAST_30D("近 30 天"),
    LAST_90D("近 90 天"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    LAST_YEAR("去年");

    private final String label;

    OrderTimeRange(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    TimeRange resolve(LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return switch (this) {
            case LAST_7D -> new TimeRange(today.minusDays(6).atStartOfDay(),
                    today.plusDays(1).atStartOfDay());
            case LAST_30D -> new TimeRange(today.minusDays(29).atStartOfDay(),
                    today.plusDays(1).atStartOfDay());
            case LAST_90D -> new TimeRange(today.minusDays(89).atStartOfDay(),
                    today.plusDays(1).atStartOfDay());
            case THIS_MONTH -> new TimeRange(firstOfMonth.atStartOfDay(),
                    today.plusDays(1).atStartOfDay());
            case LAST_MONTH -> new TimeRange(firstOfMonth.minusMonths(1).atStartOfDay(),
                    firstOfMonth.atStartOfDay());
            case LAST_YEAR -> new TimeRange(
                    LocalDate.of(today.getYear() - 1, 1, 1).atStartOfDay(),
                    LocalDate.of(today.getYear(), 1, 1).atStartOfDay());
        };
    }

    record TimeRange(LocalDateTime start, LocalDateTime end) {
    }
}
