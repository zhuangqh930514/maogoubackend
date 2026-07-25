package com.maogou.stock.schema;

import com.maogou.stock.mapper.research.AiResearchOperationsOverviewMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the operator overview mapper against MySQL 8. Mock-based service tests cannot catch
 * a MySQL parser, alias, or result-mapping regression in the read-only dashboard queries.
 */
@Testcontainers(disabledWithoutDocker = false)
class AiResearchOperationsOverviewMapperMySqlContractTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_operations_mapper_test")
            .withUsername("maogou")
            .withPassword("maogou-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            resetTables(connection);
            execute(connection, "db/legacy-ai-research-schema.sql");
            execute(connection, "db/20260714_ai_research_unified.sql");
        }
    }

    @Test
    void executesOverviewQueriesAndReturnsOnlyPersistedLineageEvidence() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO ai_research_universe
                            (id, universe_code, universe_name, market_code, selection_policy_json, minimum_stock_count, enabled)
                        VALUES (11, 'TEST_UNIVERSE', '测试研究池', 'CN_A', '{}', 1, 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_research_universe_snapshot
                            (id, research_universe_id, trade_date, as_of_time, universe_version, calendar_version,
                             source_fingerprint, item_count, quality_status, status)
                        VALUES (21, 11, '2026-07-24', '2026-07-24 16:00:00.000', 'TEST/2026-07-24/R0001',
                                'calendar-test', 'snapshot-fingerprint', 1, 'READY', 'FINALIZED')
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_research_universe_item
                            (id, universe_snapshot_id, stock_code, stock_name, listed_status, source_type,
                             included, effective_from, evidence_json, source_fingerprint)
                        VALUES (31, 21, '600519', '贵州茅台', 'LISTED', 'USER_WATCHLIST', 1,
                                '2026-07-24', '{}', 'item-fingerprint')
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_research_universe_item_lineage
                            (universe_item_id, source_type, owner_user_id, source_record_id, active_at_snapshot,
                             source_fingerprint, evidence_json, observed_at)
                        VALUES (31, 'USER_WATCHLIST', 5, 91, 1, 'watch-active', '{}', '2026-07-24 16:00:00.000'),
                               (31, 'USER_HOLDING', 5, 92, 0, 'holding-invalid', '{}', '2026-07-24 16:00:00.000')
                        """);
            }
        }

        try (SqlSession session = sessionFactory().openSession()) {
            AiResearchOperationsOverviewMapper mapper = session.getMapper(AiResearchOperationsOverviewMapper.class);
            LocalDate date = LocalDate.of(2026, 7, 24);
            LocalDateTime since = LocalDateTime.of(2026, 7, 1, 0, 0);

            assertThat(mapper.selectRunStatusCounts(since)).isEmpty();
            assertThat(mapper.selectCompletedRuns(since, 20)).isEmpty();
            assertThat(mapper.selectAttentionRuns(since, 20)).isEmpty();
            assertThat(mapper.selectSampleCoverage(999L)).isEmpty();
            assertThat(mapper.selectRecentModelFailures(since, 20)).isEmpty();
            assertThat(mapper.selectModelFailureCount(since)).isZero();
            assertThat(mapper.selectEligibleUserCount()).isZero();
            assertThat(mapper.selectMissingDailyReportUserCount(date)).isZero();
            assertThat(mapper.selectUsersMissingDailyReport(date, 20)).isEmpty();
            assertThat(mapper.selectUsersMissingTwoLatestDailyReports(20)).isEmpty();
            assertThat(mapper.selectActiveHoldingCount()).isZero();
            assertThat(mapper.selectHoldingWithoutDailyConclusionCount(date)).isZero();
            assertThat(mapper.selectHoldingsWithoutDailyConclusion(date, 20)).isEmpty();
            assertThat(mapper.selectDecisionConflictCount(date)).isZero();
            assertThat(mapper.selectDecisionConflicts(date, 20)).isEmpty();
            assertThat(mapper.selectDailyDecisionWithoutReportCount(date)).isZero();
            assertThat(mapper.selectUniversePollutionCount(21L, 999L)).isZero();
            assertThat(mapper.selectUniversePollutionItems(21L, 999L, 20)).isEmpty();
            assertThat(mapper.selectUniverseLineageCount(21L)).isEqualTo(2L);
            assertThat(mapper.selectInvalidUniverseLineageCount(21L)).isEqualTo(1L);
            assertThat(mapper.selectInvalidUniverseLineages(21L, 20)).singleElement().satisfies(row -> {
                assertThat(row.stockCode).isEqualTo("600519");
                assertThat(row.sourceType).isEqualTo("USER_HOLDING");
                assertThat(row.ownerUserId).isEqualTo(5L);
                assertThat(row.sourceRecordId).isEqualTo(92L);
                assertThat(row.activeAtSnapshot).isZero();
            });
        }
    }

    private static SqlSessionFactory sessionFactory() {
        DataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("mysql-contract", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiResearchOperationsOverviewMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void resetTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SHOW TABLES")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            for (String table : tables) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void execute(Connection connection, String resource) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(resource), StandardCharsets.UTF_8));
    }
}
