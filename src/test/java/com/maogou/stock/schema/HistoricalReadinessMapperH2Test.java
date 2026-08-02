package com.maogou.stock.schema;

import com.maogou.stock.domain.entity.research.AiHistoricalReadinessSummary;
import com.maogou.stock.mapper.research.AiHistoricalReadinessMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalReadinessMapperH2Test {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 1, 18, 0);

    @Test
    void aggregatesCurrentVersionFactsAndPITQualitySignals() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:readiness;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        createTables(dataSource);
        seed(dataSource);

        try (SqlSession session = factory(dataSource).openSession()) {
            AiHistoricalReadinessMapper mapper = session.getMapper(AiHistoricalReadinessMapper.class);
            AiHistoricalReadinessSummary summary = mapper.selectSummary(
                    1L, "POINT_IN_TIME/1.1.0", "LABEL/1.1.0", START, END, AS_OF);

            assertThat(summary).isNotNull();
            assertThat(summary.tradingDays).isEqualTo(1);
            assertThat(summary.stockCount).isEqualTo(1);
            assertThat(summary.tradabilityEligible).isEqualTo(1);
            assertThat(summary.tradabilityReady).isEqualTo(1);
            assertThat(summary.universeReady).isEqualTo(1);
            assertThat(summary.sectorReady).isEqualTo(1);
            assertThat(mapper.selectHorizonCounts(
                    1L, "POINT_IN_TIME/1.1.0", "LABEL/1.1.0", START, END, AS_OF).get(0).metricCount)
                    .isEqualTo(1);
            assertThat(mapper.selectFeatureCoverage(
                    1L, "POINT_IN_TIME/1.1.0", "FACTOR/1.1.0", START, END, AS_OF).get(0).readyCount)
                    .isEqualTo(1);
            assertThat(mapper.countPointInTimeViolations(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isZero();
        }
    }

    @Test
    void detectsNonRealtimeAndInferredSourceRows() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:readiness-quality;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        createTables(dataSource);
        seed(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ai_source_observation SET provider_code='MOCK', "
                    + "freshness_status='STALE', quality_status='INFERRED', missing_reason='INFERRED_SOURCE' "
                    + "WHERE id=1");
            statement.executeUpdate("UPDATE ai_source_observation SET available_at='2026-08-02 18:00:00' WHERE id=1");
        }

        try (SqlSession session = factory(dataSource).openSession()) {
            AiHistoricalReadinessMapper mapper = session.getMapper(AiHistoricalReadinessMapper.class);
            assertThat(mapper.countPointInTimeViolations(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isEqualTo(1);
            assertThat(mapper.countMockSources(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isEqualTo(1);
            assertThat(mapper.countStaleSources(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isEqualTo(1);
            assertThat(mapper.countInferredFacts(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isEqualTo(1);
        }
    }

    @Test
    void doesNotMixFactsFromAnotherHistoricalRun() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:readiness-lineage;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        createTables(dataSource);
        seed(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_data_batch(id,backfill_run_id) VALUES (2,2)");
            statement.executeUpdate("INSERT INTO ai_research_universe_snapshot "
                    + "(id,trade_date,status,quality_status,point_in_time_status,source_observed_at) "
                    + "VALUES (2,'2026-07-01','FINALIZED','READY','READY','2026-07-01 16:00:00')");
            statement.executeUpdate("INSERT INTO ai_research_universe_item "
                    + "(id,universe_snapshot_id,stock_code,included,listed_status,effective_from) "
                    + "VALUES (2,2,'000001',1,'LISTED','2020-01-01')");
            statement.executeUpdate("INSERT INTO ai_sample "
                    + "(id,data_batch_id,universe_item_id,stock_code,trade_date,feature_version,quality_status,tradable_status,as_of_time,market_regime) "
                    + "VALUES (2,2,2,'000001','2026-07-01','POINT_IN_TIME/1.1.0','READY','TRADABLE','2026-07-01 16:00:00','DOWN')");
            statement.executeUpdate("INSERT INTO ai_sample_label "
                    + "(id,sample_id,stock_code,horizon_trading_days,label_version,label_status,execution_status,fill_status,is_current,label_available_at,entry_trade_date,sector_excess_return,sector_membership_fingerprint,actual_direction) "
                    + "VALUES (2,2,'000001',1,'LABEL/1.1.0','MATURED','EXECUTED','FILLED',1,'2026-07-02 16:00:00','2026-07-02',0.02,'other-fp','DOWN')");
            statement.executeUpdate("INSERT INTO ai_security_daily_state "
                    + "(id,stock_code,trade_date,source_batch_id,is_current,quality_status,buy_tradable) "
                    + "VALUES (2,'000001','2026-07-02',2,1,'READY',1)");
            statement.executeUpdate("INSERT INTO ai_factor_value(id,sample_id,factor_definition_id,missing,normalized_value) "
                    + "VALUES (2,2,1,0,0.4)");
            statement.executeUpdate("INSERT INTO ai_source_observation "
                    + "(id,data_batch_id,provider_code,source_type,source_revision,available_at,freshness_status,quality_status,missing_reason) "
                    + "VALUES (2,2,'REAL_PROVIDER','DAILY_BAR','2026.1','2026-07-01 16:00:00','REALTIME','READY',NULL)");
        }

        try (SqlSession session = factory(dataSource).openSession()) {
            AiHistoricalReadinessMapper mapper = session.getMapper(AiHistoricalReadinessMapper.class);
            AiHistoricalReadinessSummary summary = mapper.selectSummary(
                    1L, "POINT_IN_TIME/1.1.0", "LABEL/1.1.0", START, END, AS_OF);
            assertThat(summary.tradingDays).isEqualTo(1);
            assertThat(summary.stockCount).isEqualTo(1);
            assertThat(summary.tradabilityEligible).isEqualTo(1);
            assertThat(summary.tradabilityReady).isEqualTo(1);
            assertThat(mapper.selectRegimeDays(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF))
                    .extracting(metric -> metric.dimensionKey)
                    .containsExactly("UP");
            assertThat(mapper.countMockSources(
                    1L, "POINT_IN_TIME/1.1.0", START, END, AS_OF)).isZero();
        }
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("h2", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiHistoricalReadinessMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void createTables(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ai_data_batch ("
                    + "id BIGINT PRIMARY KEY, backfill_run_id BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_research_universe_snapshot ("
                    + "id BIGINT PRIMARY KEY, trade_date DATE, status VARCHAR(32), quality_status VARCHAR(32), "
                    + "point_in_time_status VARCHAR(32), source_observed_at TIMESTAMP)");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_research_universe_item ("
                    + "id BIGINT PRIMARY KEY, universe_snapshot_id BIGINT, stock_code VARCHAR(16), included INT, "
                    + "listed_status VARCHAR(32), effective_from DATE, effective_to DATE)");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_sample ("
                    + "id BIGINT PRIMARY KEY, data_batch_id BIGINT, universe_item_id BIGINT, stock_code VARCHAR(16), trade_date DATE, "
                    + "feature_version VARCHAR(64), quality_status VARCHAR(32), tradable_status VARCHAR(32), "
                    + "as_of_time TIMESTAMP, market_regime VARCHAR(32))");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_sample_label ("
                    + "id BIGINT PRIMARY KEY, sample_id BIGINT, stock_code VARCHAR(16), horizon_trading_days INT, "
                    + "label_version VARCHAR(32), label_status VARCHAR(32), execution_status VARCHAR(32), "
                    + "fill_status VARCHAR(32), is_current INT, label_available_at TIMESTAMP, entry_trade_date DATE, "
                    + "sector_excess_return DECIMAL(12,6), sector_membership_fingerprint VARCHAR(128), "
                    + "actual_direction VARCHAR(16))");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_security_daily_state ("
                    + "id BIGINT PRIMARY KEY, stock_code VARCHAR(16), trade_date DATE, source_batch_id BIGINT, is_current INT, "
                    + "quality_status VARCHAR(32), buy_tradable INT)");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_factor_definition ("
                    + "id BIGINT PRIMARY KEY, factor_code VARCHAR(64), factor_version VARCHAR(32), enabled INT)");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_factor_value ("
                    + "id BIGINT PRIMARY KEY, sample_id BIGINT, factor_definition_id BIGINT, missing INT, "
                    + "normalized_value DECIMAL(18,8))");
            statement.execute("CREATE TABLE IF NOT EXISTS ai_source_observation ("
                    + "id BIGINT PRIMARY KEY, data_batch_id BIGINT, provider_code VARCHAR(32), "
                    + "source_type VARCHAR(32), source_revision VARCHAR(64), available_at TIMESTAMP, "
                    + "freshness_status VARCHAR(32), quality_status VARCHAR(32), missing_reason VARCHAR(255))");
        }
    }

    private static void seed(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_data_batch(id,backfill_run_id) VALUES (1,1)");
            statement.executeUpdate("INSERT INTO ai_research_universe_snapshot "
                    + "(id,trade_date,status,quality_status,point_in_time_status,source_observed_at) "
                    + "VALUES (1,'2026-07-01','FINALIZED','READY','READY','2026-07-01 16:00:00')");
            statement.executeUpdate("INSERT INTO ai_research_universe_item "
                    + "(id,universe_snapshot_id,stock_code,included,listed_status,effective_from) "
                    + "VALUES (1,1,'600519',1,'LISTED','2020-01-01')");
            statement.executeUpdate("INSERT INTO ai_sample "
                    + "(id,data_batch_id,universe_item_id,stock_code,trade_date,feature_version,quality_status,tradable_status,as_of_time,market_regime) "
                    + "VALUES (1,1,1,'600519','2026-07-01','POINT_IN_TIME/1.1.0','READY','TRADABLE','2026-07-01 16:00:00','UP')");
            statement.executeUpdate("INSERT INTO ai_sample_label "
                    + "(id,sample_id,stock_code,horizon_trading_days,label_version,label_status,execution_status,fill_status,is_current,label_available_at,entry_trade_date,sector_excess_return,sector_membership_fingerprint,actual_direction) "
                    + "VALUES (1,1,'600519',1,'LABEL/1.1.0','MATURED','EXECUTED','FILLED',1,'2026-07-02 16:00:00','2026-07-02',0.01,'sector-fp','UP')");
            statement.executeUpdate("INSERT INTO ai_security_daily_state "
                    + "(id,stock_code,trade_date,source_batch_id,is_current,quality_status,buy_tradable) "
                    + "VALUES (1,'600519','2026-07-02',1,1,'READY',1)");
            statement.executeUpdate("INSERT INTO ai_factor_definition(id,factor_code,factor_version,enabled) "
                    + "VALUES (1,'TREND','FACTOR/1.1.0',1)");
            statement.executeUpdate("INSERT INTO ai_factor_value(id,sample_id,factor_definition_id,missing,normalized_value) "
                    + "VALUES (1,1,1,0,0.5)");
            statement.executeUpdate("INSERT INTO ai_source_observation "
                    + "(id,data_batch_id,provider_code,source_type,source_revision,available_at,freshness_status,quality_status,missing_reason) "
                    + "VALUES (1,1,'REAL_PROVIDER','DAILY_BAR','2026.1','2026-07-01 16:00:00','REALTIME','READY',NULL)");
        }
    }
}
