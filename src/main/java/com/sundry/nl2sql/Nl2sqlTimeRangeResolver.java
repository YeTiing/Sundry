package com.sundry.nl2sql;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 模型只给 preset，绝对时间一律在服务端换算，避免模型编日期和时区错位。
 */
@Component
public class Nl2sqlTimeRangeResolver {

    public ValidatedPlan.TimeRange resolve(String preset) {
        String value = preset == null || preset.isBlank() ? "LAST_30D" : preset.toUpperCase();
        LocalDate today = LocalDate.now();
        return switch (value) {
            case "TODAY" -> range(today, today.plusDays(1));
            case "YESTERDAY" -> range(today.minusDays(1), today);
            case "LAST_7D" -> range(today.minusDays(6), today.plusDays(1));
            case "LAST_30D" -> range(today.minusDays(29), today.plusDays(1));
            case "THIS_MONTH" -> range(today.withDayOfMonth(1), today.plusDays(1));
            case "LAST_MONTH" -> {
                LocalDate first = today.withDayOfMonth(1);
                yield range(first.minusMonths(1), first);
            }
            case "LAST_QUARTER" -> {
                LocalDate first = today.withDayOfMonth(1);
                int quarterStartMonth = ((first.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate currentQuarter = LocalDate.of(first.getYear(), quarterStartMonth, 1);
                yield range(currentQuarter.minusMonths(3), currentQuarter);
            }
            default -> throw new Nl2sqlValidationException("INVALID_TIME_PRESET",
                    "不支持的时间范围: " + preset);
        };
    }

    private ValidatedPlan.TimeRange range(LocalDate start, LocalDate end) {
        return new ValidatedPlan.TimeRange(start.atStartOfDay(), end.atStartOfDay());
    }
}
