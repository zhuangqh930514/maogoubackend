package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = false)
class AiSectorLabelEvidenceMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_sector_label_test")
            .withUsername("maogou")
            .withPassword("maogou-test");

    @Test
    void addsIdempotentPointInTimeSectorEvidenceIndex() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ai_source_observation (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        stock_code VARCHAR(16),
                        source_type VARCHAR(32) NOT NULL,
                        quality_status VARCHAR(32) NOT NULL,
                        as_of_time DATETIME(3) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            runMigration(connection);
            runMigration(connection);

            try (var query = connection.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ai_source_observation'
                      AND index_name = 'idx_source_stock_type_quality_asof'
                    """); var rows = query.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
        }
    }

    private static void runMigration(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ClassPathResource("db/20260719_ai_sector_label_evidence.sql"),
                StandardCharsets.UTF_8));
    }
}
