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
class AiUniversePointInTimeMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_universe_pit_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_training_dataset_item");
            statement.execute("DROP TABLE IF EXISTS ai_research_universe_item");
            statement.execute("DROP TABLE IF EXISTS ai_research_universe_snapshot");
            statement.execute("""
                    CREATE TABLE ai_research_universe_snapshot (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        trade_date DATE NOT NULL,
                        calendar_version VARCHAR(64) NOT NULL,
                        source_fingerprint VARCHAR(128) NOT NULL,
                        quality_status VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE ai_research_universe_item (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        stock_code VARCHAR(16) NOT NULL,
                        effective_from DATE NOT NULL,
                        included TINYINT NOT NULL
                    ) ENGINE=InnoDB
                    """);
        }
    }

    @Test
    void addsExactPointInTimeFingerprintsToFrozenTrainingRows() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ai_training_dataset_item (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        training_dataset_id BIGINT NOT NULL,
                        label_fingerprint VARCHAR(128) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260719_ai_training_point_in_time_lineage.sql"),
                    StandardCharsets.UTF_8));

            assertThat(columns(connection, "ai_training_dataset_item"))
                    .contains("universe_fingerprint", "trading_state_fingerprint");
        }
    }

    @Test
    void addsPointInTimeSourceLineageAndEffectiveMembershipBoundary() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/20260719_ai_universe_point_in_time.sql"),
                    StandardCharsets.UTF_8));

            assertThat(columns(connection, "ai_research_universe_snapshot"))
                    .contains("membership_source_name", "membership_source_revision", "source_observed_at",
                            "point_in_time_status", "point_in_time_reason");
            assertThat(columns(connection, "ai_research_universe_item")).contains("effective_to");
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT LOWER(column_name)
                FROM information_schema.columns
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
