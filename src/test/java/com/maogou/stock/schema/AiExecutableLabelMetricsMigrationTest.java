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
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = false)
class AiExecutableLabelMetricsMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_label_metrics_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_label_cost_evidence");
            statement.execute("DROP TABLE IF EXISTS ai_sample_label");
            statement.execute("""
                    CREATE TABLE ai_sample_label (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        label_version VARCHAR(32) NOT NULL,
                        is_current TINYINT NOT NULL,
                        entry_trade_date DATE NULL,
                        exit_trade_date DATE NULL,
                        excess_return DECIMAL(12,6) NULL,
                        max_adverse_return DECIMAL(12,6) NULL,
                        actual_direction VARCHAR(16) NULL,
                        execution_status VARCHAR(32) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE ai_label_cost_evidence (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        slippage_bps DECIMAL(12,4) NOT NULL,
                        slippage_amount DECIMAL(20,6) NOT NULL
                    ) ENGINE=InnoDB
                    """);
        }
    }

    @Test
    void addsExecutableFillRiskAndImpactCostEvidence() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260719_ai_executable_label_metrics.sql"),
                    StandardCharsets.UTF_8));

            assertThat(columns(connection, "ai_sample_label"))
                    .contains("planned_exit_trade_date", "exit_delay_trading_days", "sector_excess_return",
                            "max_drawdown", "holding_volatility", "holding_trading_days", "fill_status");
            assertThat(columns(connection, "ai_label_cost_evidence"))
                    .contains("impact_cost_bps", "impact_cost_amount");
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return result;
    }
}
