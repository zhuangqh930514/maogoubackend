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

@Testcontainers(disabledWithoutDocker = false)
class AiResearchRevisionLineageMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_revision_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            statement.execute("DROP TABLE IF EXISTS ai_pipeline_step");
            statement.execute("DROP TABLE IF EXISTS ai_pipeline_run");
            statement.execute("DROP TABLE IF EXISTS ai_factor_performance");
            statement.execute("DROP TABLE IF EXISTS ai_sample_label");
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            statement.execute("""
                    CREATE TABLE ai_sample_label (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        sample_id BIGINT NOT NULL,
                        horizon_trading_days INT NOT NULL,
                        label_version VARCHAR(32) NOT NULL,
                        input_fingerprint VARCHAR(128) NOT NULL,
                        UNIQUE KEY uk_sample_label_version
                            (sample_id, horizon_trading_days, label_version)
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE ai_factor_performance (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        factor_definition_id BIGINT NOT NULL,
                        horizon_trading_days INT NOT NULL,
                        market_regime VARCHAR(32) NOT NULL,
                        window_type VARCHAR(32) NOT NULL,
                        window_start_date DATE NOT NULL,
                        window_end_date DATE NOT NULL,
                        input_fingerprint VARCHAR(128) NOT NULL,
                        UNIQUE KEY uk_factor_performance_window
                            (factor_definition_id, horizon_trading_days, market_regime,
                             window_type, window_start_date, window_end_date)
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE ai_pipeline_run (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        error_message TEXT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE ai_pipeline_step (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        error_message TEXT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    INSERT INTO ai_sample_label
                        (id, sample_id, horizon_trading_days, label_version, input_fingerprint)
                    VALUES (11, 101, 3, 'LABEL/1.0.0', 'label-v1')
                    """);
            statement.execute("""
                    INSERT INTO ai_factor_performance
                        (id, factor_definition_id, horizon_trading_days, market_regime,
                         window_type, window_start_date, window_end_date, input_fingerprint)
                    VALUES (21, 201, 3, 'SIDEWAYS', 'ROLLING_60D',
                            '2026-05-01', '2026-06-30', 'factor-v1')
                    """);
        }
    }

    @Test
    void upgradesExistingRowsAndAllowsAppendOnlyRevisions() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260718_ai_research_revision_lineage.sql"),
                    StandardCharsets.UTF_8));

            try (Statement statement = connection.createStatement()) {
                assertThat(singleInt(statement,
                        "SELECT revision_no FROM ai_sample_label WHERE id = 11")).isEqualTo(1);
                assertThat(singleInt(statement,
                        "SELECT is_current FROM ai_sample_label WHERE id = 11")).isEqualTo(1);
                assertThat(singleInt(statement,
                        "SELECT revision_no FROM ai_factor_performance WHERE id = 21")).isEqualTo(1);
                assertThat(singleInt(statement,
                        "SELECT is_current FROM ai_factor_performance WHERE id = 21")).isEqualTo(1);
                assertThat(columnExists(connection, "ai_pipeline_run", "error_detail")).isTrue();
                assertThat(columnExists(connection, "ai_pipeline_step", "error_detail")).isTrue();

                statement.execute("UPDATE ai_sample_label SET is_current = 0 WHERE id = 11");
                statement.execute("""
                        INSERT INTO ai_sample_label
                            (sample_id, horizon_trading_days, label_version, revision_no,
                             is_current, supersedes_label_id, revision_reason, input_fingerprint)
                        VALUES (101, 3, 'LABEL/1.0.0', 2, 1, 11,
                                'SOURCE_EVIDENCE_CHANGED', 'label-v2')
                        """);
                statement.execute("UPDATE ai_factor_performance SET is_current = 0 WHERE id = 21");
                statement.execute("""
                        INSERT INTO ai_factor_performance
                            (factor_definition_id, horizon_trading_days, market_regime,
                             window_type, window_start_date, window_end_date, revision_no,
                             is_current, supersedes_performance_id, revision_reason, input_fingerprint)
                        VALUES (201, 3, 'SIDEWAYS', 'ROLLING_60D',
                                '2026-05-01', '2026-06-30', 2, 1, 21,
                                'SOURCE_EVIDENCE_CHANGED', 'factor-v2')
                        """);

                assertThat(singleInt(statement,
                        "SELECT COUNT(*) FROM ai_sample_label WHERE sample_id = 101")).isEqualTo(2);
                assertThat(singleInt(statement,
                        "SELECT COUNT(*) FROM ai_sample_label WHERE sample_id = 101 AND is_current = 1"))
                        .isEqualTo(1);
                assertThat(singleInt(statement,
                        "SELECT COUNT(*) FROM ai_factor_performance WHERE factor_definition_id = 201"))
                        .isEqualTo(2);
                assertThat(singleInt(statement,
                        "SELECT COUNT(*) FROM ai_factor_performance WHERE factor_definition_id = 201 AND is_current = 1"))
                        .isEqualTo(1);
            }
        }
    }

    private static int singleInt(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet result = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table, column)) {
            return result.next();
        }
    }
}
