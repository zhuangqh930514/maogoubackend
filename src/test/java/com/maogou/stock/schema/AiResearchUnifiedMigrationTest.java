package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class AiResearchUnifiedMigrationTest {

    private static final String CURRENT_SCHEMA = "db/legacy-ai-research-schema.sql";
    private static final String FRESH_SCHEMA = "db/schema.sql";
    private static final String H2_SCHEMA = "db/schema-h2-body.sql";
    private static final String MIGRATION = "db/20260714_ai_research_unified.sql";
    private static final String MATRIX = "/db/ai-research-table-matrix.txt";
    private static final String VERSION = "20260714-unified-1.1";

    private static final List<String> PROTECTED_BUSINESS_TABLES = List.of(
            "user_account", "watch_stock", "trade_record", "news_flash", "market_snapshot", "stock_kline"
    );
    private static final List<String> KEEP_TABLES = List.of(
            "ai_model_config", "ai_prompt_template", "ai_chat_session", "ai_chat_message", "ai_user_memory"
    );
    private static final Set<String> UNIFIED_TABLES = Set.of(
            "ai_research_schema_version",
            "ai_research_universe",
            "ai_research_universe_snapshot",
            "ai_research_universe_item",
            "ai_data_batch",
            "ai_source_observation",
            "ai_source_health",
            "ai_sample",
            "ai_factor_definition",
            "ai_factor_value",
            "ai_strategy_release",
            "ai_prediction",
            "ai_trading_calendar",
            "ai_sample_label",
            "ai_label_cost_evidence",
            "ai_prediction_evaluation",
            "ai_factor_performance",
            "ai_training_dataset",
            "ai_training_dataset_item",
            "ai_model_version",
            "ai_walk_forward_run",
            "ai_walk_forward_fold",
            "ai_walk_forward_baseline",
            "ai_portfolio_backtest_run",
            "ai_portfolio_backtest_daily",
            "ai_portfolio_backtest_trade",
            "ai_portfolio_backtest_position",
            "ai_shadow_evaluation",
            "ai_shadow_evaluation_item",
            "ai_drift_event",
            "ai_strategy_governance_event",
            "ai_pipeline_run",
            "ai_pipeline_step",
            "ai_analysis_report",
            "ai_analysis_report_prediction",
            "ai_trade_rule_config",
            "ai_trade_plan_review",
            "ai_trade_rule_performance",
            "ai_user_strategy_binding",
            "ai_daily_decision_snapshot",
            "ai_daily_decision_item",
            "ai_daily_decision_item_prediction",
            "ai_research_daily_report"
    );
    private static final Set<String> OBSOLETE_TABLES = Set.of(
            "ai_analysis_decision",
            "ai_analysis_factor_hit",
            "ai_analysis_outcome",
            "ai_backtest_run",
            "ai_backtest_trade",
            "ai_daily_insight_snapshot",
            "ai_daily_insight_item",
            "ai_factor_performance_v2",
            "ai_factor_stat",
            "ai_factor_value_v2",
            "ai_label_v2",
            "ai_learning_job_log",
            "ai_model_eval_run",
            "ai_prediction_label",
            "ai_prediction_result",
            "ai_prediction_sample",
            "ai_prediction_v2",
            "ai_sample_v2",
            "ai_stock_universe",
            "ai_strategy_evolution_log",
            "ai_strategy_experiment",
            "ai_strategy_version"
    );
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "uk_sample_label_version",
            "uk_prediction_evaluation",
            "uk_report_prediction",
            "uk_decision_item_prediction",
            "uk_source_health_provider",
            "uk_active_strategy_release",
            "uk_user_strategy_binding",
            "uk_current_daily_decision",
            "uk_current_research_report",
            "idx_source_stock_type_event",
            "idx_sample_trade_stock",
            "idx_prediction_strategy_horizon",
            "idx_label_maturity_status",
            "idx_pipeline_scope_trade_status",
            "idx_pipeline_retry_lease",
            "idx_decision_user_trade_action"
    );

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_migration_test")
            .withUsername("maogou")
            .withPassword("maogou-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @BeforeEach
    void resetMySqlSchema() throws SQLException {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tableNames(connection)) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    void resetMigrationBuildsOneResearchDomainWithoutTouchingProtectedData() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            executeResource(connection, CURRENT_SCHEMA);
            seedProtectedBusinessData(connection);
            seedKeepTables(connection);
            seedTradingCalendar(connection);

            Map<String, List<Map<String, Object>>> protectedBefore = snapshot(connection, PROTECTED_BUSINESS_TABLES);
            Map<String, List<Map<String, Object>>> keepBefore = snapshot(connection, KEEP_TABLES);

            String migrationSql = readRequiredResource(MIGRATION);
            assertMigrationCoversMatrix(migrationSql);
            executeResource(connection, MIGRATION);

            assertUnifiedTables(connection);
            assertProtectedBusinessRowsUnchanged(connection, protectedBefore);
            assertRowsUnchanged(connection, keepBefore);
            assertBaselineSeedIsRunnable(connection, 1001, true);
            assertMigrationVersion(connection);

            assertThatThrownBy(() -> executeResource(connection, MIGRATION))
                    .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("23000"));
        }
    }

    @Test
    void freshMySqlSchemaBuildsTheSameRunnableResearchDomain() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            executeResource(connection, FRESH_SCHEMA);

            assertUnifiedTables(connection);
            assertThat(columnNames(connection, "user_account")).contains("system_role");
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM user_account WHERE id = 1 AND system_role = 'USER'"))
                    .isEqualTo(1);
            assertBaselineSeedIsRunnable(connection, 1, false);
            assertMigrationVersion(connection);
        }
    }

    @Test
    void freshH2SchemaUsesExplicitGuardsToRejectMultipleCurrentRows() throws Exception {
        String url = "jdbc:h2:mem:ai_research_unified_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            executeResource(connection, H2_SCHEMA);

            Set<String> tables = tableNames(connection);
            assertThat(tables).containsAll(UNIFIED_TABLES);
            assertThat(tables).doesNotContainAnyElementsOf(OBSOLETE_TABLES);
            assertThat(tables).noneMatch(table -> table.endsWith("_v2"));
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM ai_strategy_release WHERE active_guard = 1"))
                    .isEqualTo(1);
            assertThat(scalarLong(connection,
                    "SELECT COUNT(*) FROM ai_user_strategy_binding WHERE current_guard = 1"))
                    .isEqualTo(scalarLong(connection, "SELECT COUNT(*) FROM user_account WHERE deleted = 0"));

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO ai_strategy_release
                        (research_universe_id, model_family, version_no, title, status, release_role,
                         active_guard, config_json)
                    VALUES
                        (1, 'A_SHARE_MULTI_HORIZON', 'H2-DUPLICATE/1.0.0', '重复当前策略',
                         'ACTIVE', 'CHAMPION', 1, '{}')
                    """))
                    .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("23505"));
        }
    }

    private static void seedProtectedBusinessData(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO user_account
                    (id, username, display_name, email, phone, password_hash, status, risk_preference,
                     last_login_at, deleted, created_at, updated_at)
                VALUES
                    (1001, 'migration-user', '迁移用户', 'migration@example.com', '13900001001',
                     'fixed-password-hash', 'ACTIVE', 'BALANCED', '2026-07-14 09:00:00', 0,
                     '2026-07-01 08:00:00', '2026-07-14 09:00:00')
                """);
        execute(connection, """
                INSERT INTO watch_stock
                    (id, user_id, stock_code, stock_name, market, group_name, priority, deleted, created_at, updated_at)
                VALUES
                    (1101, 1001, '600519', '贵州茅台', 'SH', '核心', 10, 0,
                     '2026-07-01 08:01:00', '2026-07-14 09:01:00')
                """);
        execute(connection, """
                INSERT INTO trade_record
                    (id, user_id, stock_code, stock_name, side, price, quantity, fee, traded_at,
                     deleted, created_at, updated_at)
                VALUES
                    (1201, 1001, '600519', '贵州茅台', 'BUY', 1420.5000, 100, 8.5000,
                     '2026-07-10 10:30:00', 0, '2026-07-10 10:30:01', '2026-07-10 10:30:01')
                """);
        execute(connection, """
                INSERT INTO news_flash (id, title, source, url, published_at, created_at)
                VALUES (1301, '固定迁移资讯', 'TEST', 'https://example.com/news/1301',
                        '2026-07-14 08:30:00', '2026-07-14 08:31:00')
                """);
        execute(connection, """
                INSERT INTO market_snapshot
                    (id, symbol, name, market, latest_price, change_amount, change_percent,
                     volume_ratio, amount, quote_time, created_at)
                VALUES
                    (1401, '000001', '上证指数', 'SH', 3500.1200, 12.3400, 0.3540,
                     1.1200, 500000000000.0000, '2026-07-14 15:00:00', '2026-07-14 15:00:01')
                """);
        execute(connection, """
                INSERT INTO stock_kline
                    (id, stock_code, period, trade_date, open_price, close_price, low_price,
                     high_price, volume, amount, created_at)
                VALUES
                    (1501, '600519', 'day', '2026-07-14', 1410.0000, 1425.0000, 1405.0000,
                     1430.0000, 1234567, 1750000000.0000, '2026-07-14 15:01:00')
                """);
    }

    private static void seedKeepTables(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO ai_model_config
                    (id, user_id, api_base_url, model_name, api_key, timeout_ms, temperature, max_tokens,
                     intraday_interval_minutes, close_analysis_time, analysis_scope, prompt_template,
                     auto_close_pipeline_enabled, auto_close_pipeline_last_status, deleted, created_at, updated_at)
                VALUES
                    (2001, 1001, 'http://127.0.0.1:11434/v1', 'qwen3.6', 'fixed-key', 45000, 0.15, 4096,
                     20, '16:00', '全部自选股', '固定提示词', 1, 'SUCCESS', 0,
                     '2026-07-01 08:10:00', '2026-07-14 08:10:00')
                """);
        execute(connection, """
                INSERT INTO ai_prompt_template (id, user_id, title, content, deleted, created_at, updated_at)
                VALUES (2002, 1001, '固定模板', '仅使用时点可见数据', 0,
                        '2026-07-01 08:11:00', '2026-07-14 08:11:00')
                """);
        execute(connection, """
                INSERT INTO ai_chat_session (id, user_id, title, model_name, deleted, created_at, updated_at)
                VALUES (2003, 1001, '固定会话', 'qwen3.6', 0,
                        '2026-07-01 08:12:00', '2026-07-14 08:12:00')
                """);
        execute(connection, """
                INSERT INTO ai_chat_message
                    (id, session_id, user_id, message_role, content, model_name, status, error_message, created_at)
                VALUES (2004, 2003, 1001, 'user', '固定消息', 'qwen3.6', 'SUCCESS', NULL,
                        '2026-07-01 08:13:00')
                """);
        execute(connection, """
                INSERT INTO ai_user_memory
                    (id, user_id, memory_summary, last_interaction_at, created_at, updated_at)
                VALUES (2005, 1001, '固定记忆', '2026-07-14 08:14:00',
                        '2026-07-01 08:14:00', '2026-07-14 08:14:00')
                """);
    }

    private static void seedTradingCalendar(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO ai_trading_calendar
                    (id, market_code, trade_date, calendar_version, is_trade_day, session_open_time,
                     session_close_time, previous_trade_date, next_trade_date, source_name, source_as_of,
                     source_fingerprint, created_at)
                VALUES
                    (3001, 'CN_A', '2026-07-15', 'LEGACY-CALENDAR-2026', 1, '09:30:00', '15:00:00',
                     '2026-07-14', '2026-07-16', 'MIGRATION_TEST', '2026-07-14 18:00:00',
                     'calendar-fixed-fingerprint', '2026-07-14 18:00:01'),
                    (3002, 'CN_A', CURRENT_DATE + INTERVAL 14 DAY, 'MIGRATION-GUARD-CALENDAR', 1,
                     '09:30:00', '15:00:00', CURRENT_DATE + INTERVAL 13 DAY,
                     CURRENT_DATE + INTERVAL 15 DAY, 'MIGRATION_TEST', CURRENT_TIMESTAMP(3),
                     'calendar-dynamic-coverage-fingerprint', CURRENT_TIMESTAMP(3))
                """);
    }

    private static void assertMigrationCoversMatrix(String migrationSql) throws Exception {
        String normalized = migrationSql.toLowerCase(Locale.ROOT);
        for (String line : readRequiredResource(MATRIX.substring(1)).lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", -1);
            String action = parts[0];
            String table = parts[1];
            String dropStatement = "drop table if exists " + table.toLowerCase(Locale.ROOT);
            if ("KEEP".equals(action)) {
                assertThat(normalized).doesNotContain(dropStatement);
            } else {
                assertThat(normalized).contains(dropStatement);
            }
        }
    }

    private static void assertUnifiedTables(Connection connection) throws SQLException {
        Set<String> actualTables = tableNames(connection);
        assertThat(actualTables).containsAll(UNIFIED_TABLES);
        assertThat(actualTables).doesNotContainAnyElementsOf(OBSOLETE_TABLES);
        assertThat(actualTables).noneMatch(table -> table.endsWith("_v2"));

        assertThat(columnNames(connection, "user_account")).contains("system_role");
        assertThat(columnNames(connection, "ai_data_batch")).doesNotContain("user_id");
        assertThat(columnNames(connection, "ai_prediction"))
                .contains("horizon_trading_days", "sample_id", "strategy_release_id")
                .doesNotContain("user_id");
        assertThat(columnNames(connection, "ai_sample_label"))
                .contains("sample_id", "horizon_trading_days", "label_available_at")
                .doesNotContain("prediction_id", "user_id");
        assertThat(columnNames(connection, "ai_pipeline_run"))
                .contains("scope_type", "owner_user_id", "parent_run_id", "next_retry_at", "lease_until")
                .doesNotContain("user_id");
        assertThat(columnNames(connection, "ai_daily_decision_item"))
                .contains("system_score", "final_action", "risk_score", "risk_level",
                        "decision_source", "freshness_status", "decision_policy_version");

        Set<String> indexes = indexNames(connection);
        assertThat(indexes).containsAll(REQUIRED_INDEXES);
    }

    private static void assertProtectedBusinessRowsUnchanged(
            Connection connection,
            Map<String, List<Map<String, Object>>> before
    ) throws SQLException {
        Map<String, List<Map<String, Object>>> after = snapshot(connection, PROTECTED_BUSINESS_TABLES);
        assertThat(after.get("user_account")).allSatisfy(row -> {
            assertThat(row.remove("system_role")).isEqualTo("USER");
        });
        assertThat(after).isEqualTo(before);
    }

    private static void assertRowsUnchanged(
            Connection connection,
            Map<String, List<Map<String, Object>>> before
    ) throws SQLException {
        assertThat(snapshot(connection, new ArrayList<>(before.keySet()))).isEqualTo(before);
    }

    private static void assertBaselineSeedIsRunnable(
            Connection connection,
            long userId,
            boolean assertMigratedCalendar
    ) throws SQLException {
        assertThat(scalarLong(connection,
                "SELECT COUNT(*) FROM ai_factor_definition WHERE enabled = 1 AND seed_version = '" + VERSION + "'"))
                .isGreaterThanOrEqualTo(5);
        assertThat(scalarLong(connection, """
                SELECT COUNT(*) FROM ai_strategy_release
                WHERE release_role = 'CHAMPION' AND status = 'ACTIVE' AND seed_version = '20260714-unified-1.1'
                """)).isEqualTo(1);
        assertThat(scalarLong(connection, """
                SELECT COUNT(*) FROM ai_user_strategy_binding
                WHERE is_current = 1 AND seed_version = '20260714-unified-1.1'
                """)).isEqualTo(scalarLong(connection, "SELECT COUNT(*) FROM user_account WHERE deleted = 0"));
        assertThat(scalarLong(connection, """
                SELECT COUNT(*) FROM ai_trade_rule_config
                WHERE user_id = %d AND status = 'ACTIVE' AND seed_version = '20260714-unified-1.1'
                """.formatted(userId))).isEqualTo(1);
        if (assertMigratedCalendar) {
            assertThat(scalarLong(connection, """
                    SELECT COUNT(*) FROM ai_trading_calendar
                    WHERE id = 3001 AND market_code = 'CN_A' AND trade_date = '2026-07-15'
                      AND source_fingerprint = 'calendar-fixed-fingerprint'
                    """)).isEqualTo(1);
        }

        long globalRunId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_pipeline_run
                    (scope_type, owner_user_id, parent_run_id, trade_date, pipeline_type,
                     idempotency_key, input_fingerprint, status)
                VALUES ('GLOBAL', NULL, NULL, '2026-07-15', 'GLOBAL_DAILY_RESEARCH',
                        ?, ?, 'PENDING')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "schema-test-global-run-" + userId);
            statement.setString(2, "schema-test-global-input-" + userId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                globalRunId = keys.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_pipeline_run
                    (scope_type, owner_user_id, parent_run_id, trade_date, pipeline_type,
                     idempotency_key, input_fingerprint, status)
                VALUES ('USER', ?, ?, '2026-07-15', 'USER_DAILY_PROJECTION',
                        ?, ?, 'PENDING')
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, globalRunId);
            statement.setString(3, "schema-test-user-run-" + userId);
            statement.setString(4, "schema-test-user-input-" + userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void assertMigrationVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, schema_checksum, completed_at
                FROM ai_research_schema_version
                WHERE version_no = ?
                """)) {
            statement.setString(1, VERSION);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("APPLIED");
                assertThat(result.getString("schema_checksum")).isEqualTo("AI_RESEARCH_UNIFIED_1_1");
                assertThat(result.getTimestamp("completed_at")).isNotNull();
            }
        }
    }

    private static Map<String, List<Map<String, Object>>> snapshot(
            Connection connection,
            List<String> tables
    ) throws SQLException {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), result.getObject(column));
                    }
                    rows.add(row);
                }
            }
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private static Set<String> columnNames(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet result = connection.getMetaData()
                .getColumns(connection.getCatalog(), null, table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static Set<String> indexNames(Connection connection) throws SQLException {
        Set<String> indexes = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                indexes.add(result.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return indexes;
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void executeResource(Connection connection, String resource) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(resource), StandardCharsets.UTF_8));
    }

    private static String readRequiredResource(String resource) throws Exception {
        try (InputStream input = AiResearchUnifiedMigrationTest.class.getResourceAsStream("/" + resource)) {
            assertThat(input).as("required SQL resource /%s must exist", resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String findSqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
