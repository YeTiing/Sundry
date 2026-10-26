package com.sundry.nl2sql;

import com.sundry.nl2sql.dto.Nl2sqlPlanResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Nl2sqlPlanValidatorTest {

    private final SemanticLayerProvider provider = new SemanticLayerProvider();
    private final Nl2sqlPlanValidator validator =
            new Nl2sqlPlanValidator(provider, new Nl2sqlTimeRangeResolver());
    private final Nl2sqlQueryBuilder builder = new Nl2sqlQueryBuilder(provider);

    @Test
    void buildsParameterizedSql() {
        ValidatedPlan plan = validator.validate(plan(
                List.of("gmv"), List.of("category"), List.of(), "LAST_MONTH",
                "day", List.of()));

        QuerySpec spec = builder.build(plan);

        assertThat(spec.sql())
                .contains("SELECT c.name AS category, SUM(oi.price * oi.quantity) AS gmv")
                .contains("WHERE 1 = 1")
                .contains("o.paid_at >= ? AND o.paid_at < ?")
                .contains("GROUP BY c.name")
                .contains("ORDER BY gmv DESC")
                .contains("LIMIT ?")
                .doesNotContain("LAST_MONTH");
        assertThat(spec.params()).hasSize(3);
        assertThat(spec.params().get(2)).isEqualTo(10);
    }

    @Test
    void filtersAreBoundAsParameters() {
        Nl2sqlPlanResponse.Plan plan = new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of(), List.of(
                        new Nl2sqlPlanResponse.Plan.Filter("order_source", "SECKILL")),
                new Nl2sqlPlanResponse.Plan.Time("LAST_7D"), "day", List.of(), 5);

        ValidatedPlan validated = validator.validate(plan);
        QuerySpec spec = builder.build(validated);

        assertThat(spec.sql()).contains("o.order_source = ?");
        assertThat(spec.params()).containsExactly(
                validated.time().start(),
                validated.time().end(),
                "SECKILL", 5);
    }

    @Test
    void rejectsUnknownMetric() {
        assertCode(() -> validator.validate(plan(
                List.of("password_hash"), List.of(), List.of(), "LAST_30D",
                "day", List.of())), "UNSUPPORTED_METRIC");
    }

    @Test
    void rejectsInvalidEnumValue() {
        Nl2sqlPlanResponse.Plan plan = new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of(), List.of(
                        new Nl2sqlPlanResponse.Plan.Filter("status", "HACKED")),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 10);
        assertCode(() -> validator.validate(plan), "INVALID_ENUM_VALUE");
    }

    @Test
    void rejectsRefundMetricByProduct() {
        assertCode(() -> validator.validate(plan(
                List.of("refund_rate"), List.of("product"), List.of(), "LAST_30D",
                "day", List.of())), "INCOMPATIBLE_PAIR");
    }

    @Test
    void rejectsTooManyMetricsAndBadLimit() {
        assertCode(() -> validator.validate(plan(
                List.of("gmv", "paid_order_count", "avg_order_value", "product_sold_count"),
                List.of(), List.of(), "LAST_30D", "day", List.of())), "TOO_MANY_METRICS");

        Nl2sqlPlanResponse.Plan badLimit = new Nl2sqlPlanResponse.Plan(
                List.of("gmv"), List.of(), List.of(),
                new Nl2sqlPlanResponse.Plan.Time("LAST_30D"), "day", List.of(), 5000);
        assertCode(() -> validator.validate(badLimit), "INVALID_LIMIT");
    }

    private Nl2sqlPlanResponse.Plan plan(List<String> metrics, List<String> dimensions,
                                         List<Nl2sqlPlanResponse.Plan.Filter> filters,
                                         String preset, String granularity,
                                         List<Nl2sqlPlanResponse.Plan.Sort> sort) {
        return new Nl2sqlPlanResponse.Plan(metrics, dimensions, filters,
                new Nl2sqlPlanResponse.Plan.Time(preset), granularity, sort, 10);
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(Nl2sqlValidationException.class)
                .satisfies(ex -> assertThat(((Nl2sqlValidationException) ex).getCode())
                        .isEqualTo(code));
    }
}
