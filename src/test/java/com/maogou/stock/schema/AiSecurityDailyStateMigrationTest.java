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
class AiSecurityDailyStateMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_security_state_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_security_daily_state");
        }
    }

    @Test
    void createsAppendOnlyRevisionTableWithOneCurrentStatePerStockDay() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260719_ai_security_daily_state.sql"), StandardCharsets.UTF_8));
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO ai_security_daily_state
                            (stock_code, trade_date, source_revision, revision_no, is_current,
                             security_status, st_status, quality_status, evidence_json,
                             source_fingerprint, observed_at)
                        VALUES ('600519', '2026-07-17', 'v1', 1, 1,
                                'LISTED', 'UNKNOWN', 'PARTIAL', '{}', 'fingerprint-v1',
                                '2026-07-17 15:05:00')
                        """);
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO ai_security_daily_state
                            (stock_code, trade_date, source_revision, revision_no, is_current,
                             security_status, st_status, quality_status, evidence_json,
                             source_fingerprint, observed_at)
                        VALUES ('600519', '2026-07-17', 'v2', 2, 1,
                                'LISTED', 'UNKNOWN', 'PARTIAL', '{}', 'fingerprint-v2',
                                '2026-07-17 15:06:00')
                        """))
                        .isInstanceOf(Exception.class);
                statement.execute("UPDATE ai_security_daily_state SET is_current = 0 WHERE revision_no = 1");
                statement.execute("""
                        INSERT INTO ai_security_daily_state
                            (stock_code, trade_date, source_revision, revision_no, is_current,
                             security_status, st_status, quality_status, evidence_json,
                             source_fingerprint, observed_at)
                        VALUES ('600519', '2026-07-17', 'v2', 2, 1,
                                'LISTED', 'UNKNOWN', 'PARTIAL', '{}', 'fingerprint-v2',
                                '2026-07-17 15:06:00')
                        """);
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*) FROM ai_security_daily_state
                        WHERE stock_code = '600519' AND trade_date = '2026-07-17' AND is_current = 1
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }
    }
}
