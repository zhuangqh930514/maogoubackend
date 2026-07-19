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
class AiSectorMembershipLineageMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_sector_lineage_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_training_dataset_item");
            statement.execute("DROP TABLE IF EXISTS ai_sample_label");
            statement.execute("""
                    CREATE TABLE ai_sample_label (
                        id BIGINT PRIMARY KEY,
                        label_version VARCHAR(32) NOT NULL,
                        is_current TINYINT NOT NULL,
                        sector_excess_return DECIMAL(12, 6) NULL,
                        label_available_at DATETIME(3) NULL
                    )
                    """);
            statement.execute("CREATE TABLE ai_training_dataset_item (id BIGINT PRIMARY KEY, trading_state_fingerprint VARCHAR(128) NULL)");
        }
    }

    @Test
    void addsNormalizedSectorLineageIdempotently() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            execute(connection);
            execute(connection);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ai_sample_label'
                      AND column_name = 'sector_membership_fingerprint'
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ai_training_dataset_item'
                      AND column_name = 'sector_membership_fingerprint'
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'ai_sample_label'
                      AND index_name = 'idx_label_sector_evidence'
                    """)).isPositive();
        }
    }

    private static void execute(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ClassPathResource("db/20260719_ai_sector_membership_lineage.sql"),
                StandardCharsets.UTF_8));
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
