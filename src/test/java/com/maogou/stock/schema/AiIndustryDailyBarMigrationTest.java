package com.maogou.stock.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class AiIndustryDailyBarMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_industry_bar_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_industry_daily_bar");
        }
    }

    @Test
    void createsImmutableIndustryBarRevisionsAndEnforcesOhlc() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260719_ai_industry_daily_bar.sql"),
                    StandardCharsets.UTF_8));
            try (Statement statement = connection.createStatement()) {
                statement.execute(insert("fingerprint-v1", 1, 1, "102.000000", "99.000000"));
                assertThatThrownBy(() -> statement.execute(
                        insert("fingerprint-v2", 2, 1, "103.000000", "98.000000")))
                        .isInstanceOf(Exception.class);
                statement.execute("UPDATE ai_industry_daily_bar SET is_current = 0 WHERE revision_no = 1");
                statement.execute(insert("fingerprint-v2", 2, 1, "103.000000", "98.000000"));
                assertThatThrownBy(() -> statement.execute(
                        insert("fingerprint-invalid", 3, 0, "99.000000", "101.000000")))
                        .isInstanceOf(Exception.class);
                try (ResultSet rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM ai_industry_daily_bar
                        WHERE industry_code = '801120.SI' AND trade_date = '2026-07-17' AND is_current = 1
                        """)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    private static String insert(
            String fingerprint,
            int revision,
            int current,
            String high,
            String low
    ) {
        return """
                INSERT INTO ai_industry_daily_bar
                    (industry_code, industry_name, classification_standard, trade_date,
                     open_price, high_price, low_price, close_price, volume, amount,
                     source_name, source_revision, revision_no, is_current, quality_status,
                     source_ref, evidence_json, source_fingerprint, observed_at)
                VALUES ('801120.SI', '食品饮料', 'SW2021', '2026-07-17',
                        100.000000, %s, %s, 101.000000, 1000.000000, 2000.000000,
                        'TUSHARE', '20260719', %d, %d, 'READY',
                        'TUSHARE/sw_daily', '{}', '%s', '2026-07-19 09:00:00')
                """.formatted(high, low, revision, current, fingerprint);
    }
}
