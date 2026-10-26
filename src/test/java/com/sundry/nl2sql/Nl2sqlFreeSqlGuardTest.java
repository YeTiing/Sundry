package com.sundry.nl2sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Nl2sqlFreeSqlGuardTest {

    private final Nl2sqlFreeSqlGuard guard = new Nl2sqlFreeSqlGuard();

    @Test
    void allowsSafeSelectAndAddsLimit() {
        String sql = guard.validate(
                "SELECT o.status, COUNT(*) AS cnt FROM orders o "
                        + "JOIN order_item oi ON oi.order_id = o.id "
                        + "GROUP BY o.status", 1000);

        assertThat(sql).contains("LIMIT 1000");
    }

    @Test
    void keepsExistingSafeLimit() {
        String sql = guard.validate("SELECT status FROM orders LIMIT 5", 1000);
        assertThat(sql).contains("LIMIT 5");
    }

    @Test
    void allowsSchemaQualifiedTable() {
        assertThat(guard.validate("SELECT status FROM sundry.orders", 1000))
                .contains("sundry.orders");
    }

    @Test
    void rejectsNonSelectAndMultipleStatements() {
        assertRejected("DELETE FROM orders", "ONLY_SELECT");
        assertRejected("SELECT status FROM orders; DELETE FROM orders", "MULTI_STATEMENT");
    }

    @Test
    void rejectsUnionAndSubquery() {
        assertRejected("SELECT status FROM orders UNION SELECT status FROM orders",
                "ONLY_PLAIN_SELECT");
        assertRejected("SELECT (SELECT status FROM orders LIMIT 1) FROM orders",
                "SUBQUERY_NOT_ALLOWED");
        assertRejected("SELECT status FROM orders WHERE id IN (SELECT id FROM orders)",
                "SUBQUERY_NOT_ALLOWED");
    }

    @Test
    void rejectsStar() {
        assertRejected("SELECT * FROM orders", "STAR_NOT_ALLOWED");
        assertRejected("SELECT o.* FROM orders o", "STAR_NOT_ALLOWED");
    }

    @Test
    void rejectsTablesOutsideAllowlist() {
        assertRejected("SELECT id FROM app_user", "TABLE_NOT_ALLOWED");
        assertRejected("SELECT table_name FROM information_schema.tables",
                "TABLE_NOT_ALLOWED");
        assertRejected("SELECT id FROM other.orders", "TABLE_NOT_ALLOWED");
    }

    @Test
    void rejectsPiiAndUnknownColumns() {
        assertRejected("SELECT receiver_phone FROM orders", "COLUMN_NOT_ALLOWED");
        assertRejected("SELECT tracking_no FROM orders", "COLUMN_NOT_ALLOWED");
        assertRejected("SELECT password_hash FROM orders", "COLUMN_NOT_ALLOWED");
    }

    @Test
    void rejectsAmbiguousUnqualifiedColumnInJoin() {
        assertRejected("SELECT id FROM orders o JOIN order_item oi ON oi.order_id = o.id",
                "AMBIGUOUS_COLUMN");
    }

    @Test
    void rejectsDangerousFunctions() {
        assertRejected("SELECT SLEEP(5) FROM orders", "FUNCTION_NOT_ALLOWED");
        assertRejected("SELECT LOAD_FILE('/etc/passwd') FROM orders", "FUNCTION_NOT_ALLOWED");
    }

    @Test
    void rejectsInvalidLimits() {
        assertRejected("SELECT status FROM orders LIMIT 100000", "INVALID_LIMIT");
        assertRejected("SELECT status FROM orders LIMIT 5 OFFSET 999999", "INVALID_LIMIT");
    }

    private void assertRejected(String sql, String code) {
        assertThatThrownBy(() -> guard.validate(sql, 1000))
                .isInstanceOfSatisfying(Nl2sqlValidationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
