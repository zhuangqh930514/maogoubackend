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
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class AiUserNotificationMigrationTest {

    private static final String LEGACY_SCHEMA = "db/legacy-ai-research-schema.sql";
    private static final String UNIFIED_MIGRATION = "db/20260714_ai_research_unified.sql";
    private static final String NOTIFICATION_MIGRATION = "db/20260725_user_notifications.sql";
    private static final String H2_SCHEMA = "db/schema-h2-body.sql";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_notification_migration_test")
            .withUsername("maogou")
            .withPassword("maogou-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @BeforeEach
    void reset() throws SQLException {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            List<String> tableNames = new ArrayList<>();
            try (ResultSet tableRows = statement.executeQuery("SHOW TABLES")) {
                while (tableRows.next()) {
                    tableNames.add(tableRows.getString(1));
                }
            }
            for (String table : tableNames) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    void upgradesUnifiedSchemaWithDeduplicatedUserNotifications() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            executeResource(connection, LEGACY_SCHEMA);
            seedUser(connection);
            executeResource(connection, UNIFIED_MIGRATION);

            executeResource(connection, NOTIFICATION_MIGRATION);
            executeResource(connection, NOTIFICATION_MIGRATION);

            assertThat(tableExists(connection, "ai_user_notification")).isTrue();
            assertThat(indexNames(connection, "ai_user_notification"))
                    .contains("primary", "uk_ai_user_notification_dedupe",
                            "idx_ai_user_notification_recent", "idx_ai_user_notification_report");
            assertThat(importedForeignKeyNames(connection, "ai_user_notification"))
                    .contains("fk_ai_user_notification_user", "fk_ai_user_notification_report");

            insertNotification(connection, 1001L, "DAILY_REPORT:2026-07-25:DAILY_REPORT_READY", null);
            assertThatThrownBy(() ->
                    insertNotification(connection, 1001L, "DAILY_REPORT:2026-07-25:DAILY_REPORT_READY", null))
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23000"));
            assertThatThrownBy(() -> insertNotification(connection, 9999L, "unknown-user", null))
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23000"));
            assertThatThrownBy(() -> insertNotification(connection, 1001L, "unknown-report", 9999L))
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23000"));
            assertThat(scalarLong(connection, """
                    SELECT COUNT(*) FROM ai_user_notification
                    WHERE user_id = 1001 AND is_read = 0
                    """)).isEqualTo(1);
        }
    }

    @Test
    void h2InitializationSchemaSupportsNotificationTableAndUnreadIndex() throws Exception {
        String url = "jdbc:h2:mem:ai_user_notification_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            executeResource(connection, H2_SCHEMA);

            assertThat(tableExists(connection, "ai_user_notification")).isTrue();
            assertThat(indexNames(connection, "ai_user_notification"))
                    .contains("idx_ai_user_notification_recent", "idx_ai_user_notification_report");
            long userId = scalarLong(connection, "SELECT MIN(id) FROM user_account WHERE deleted = 0");
            insertNotification(connection, userId, "H2:DAILY_REPORT:2026-07-25", null);
            assertThatThrownBy(() -> insertNotification(connection, userId, "H2:DAILY_REPORT:2026-07-25", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static void seedUser(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO user_account
                        (id, username, display_name, phone, password_hash, status, risk_preference,
                         deleted, created_at, updated_at)
                    VALUES
                        (1001, 'notification-migration-user', '通知迁移用户', '13900001001',
                         'fixed-password-hash', 'ACTIVE', 'BALANCED', 0,
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }
    }

    private static void insertNotification(Connection connection, long userId, String dedupeKey, Long reportId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_user_notification
                    (user_id, notification_type, dedupe_key, level, title, content, report_id, trade_date)
                VALUES (?, 'DAILY_REPORT_READY', ?, 'INFO', '投研日报已更新', '测试通知', ?, '2026-07-25')
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, dedupeKey);
            if (reportId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, reportId);
            }
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static Set<String> indexNames(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase());
                }
            }
        }
        return names;
    }

    private static Set<String> importedForeignKeyNames(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                String name = keys.getString("FK_NAME");
                if (name != null) {
                    names.add(name.toLowerCase());
                }
            }
        }
        return names;
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static void executeResource(Connection connection, String resource) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(resource), StandardCharsets.UTF_8));
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException exception && exception.getSQLState() != null) {
                return exception.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
