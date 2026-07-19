package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AiSampleLabelMapper extends BaseMapper<AiSampleLabel> {

    @Insert("""
            <script>
            INSERT INTO ai_sample_label (
                sample_id, entry_calendar_id, exit_calendar_id, stock_code,
                horizon_trading_days, label_version, revision_no, is_current,
                supersedes_label_id, revision_reason, calendar_version, input_fingerprint,
                entry_trade_date, planned_exit_trade_date, exit_trade_date,
                exit_delay_trading_days, entry_price, exit_price,
                gross_return, net_return, benchmark_return, sector_return, excess_return,
                sector_excess_return, sector_membership_fingerprint,
                max_favorable_return, max_adverse_return,
                max_drawdown, holding_volatility, holding_trading_days,
                actual_direction, fill_status,
                execution_status, execution_reason, label_status,
                policy_snapshot_json, market_evidence_json, label_available_at,
                matured_at, verified_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.sampleId}, #{item.entryCalendarId}, #{item.exitCalendarId},
                    #{item.stockCode}, #{item.horizonTradingDays}, #{item.labelVersion},
                    #{item.revisionNo}, #{item.isCurrent}, #{item.supersedesLabelId},
                    #{item.revisionReason}, #{item.calendarVersion}, #{item.inputFingerprint}, #{item.entryTradeDate},
                    #{item.plannedExitTradeDate}, #{item.exitTradeDate}, #{item.exitDelayTradingDays},
                    #{item.entryPrice}, #{item.exitPrice},
                    #{item.grossReturn}, #{item.netReturn}, #{item.benchmarkReturn},
                    #{item.sectorReturn}, #{item.excessReturn}, #{item.sectorExcessReturn},
                    #{item.sectorMembershipFingerprint},
                    #{item.maxFavorableReturn}, #{item.maxAdverseReturn}, #{item.maxDrawdown},
                    #{item.holdingVolatility}, #{item.holdingTradingDays},
                    #{item.actualDirection}, #{item.fillStatus}, #{item.executionStatus},
                    #{item.executionReason}, #{item.labelStatus}, #{item.policySnapshotJson},
                    #{item.marketEvidenceJson}, #{item.labelAvailableAt}, #{item.maturedAt},
                    #{item.verifiedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiSampleLabel> items);

    @Update("""
            <script>
            UPDATE ai_sample_label SET is_current = 0
            WHERE is_current = 1 AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    int markSuperseded(@Param("ids") List<Long> ids);

    @Select("""
            SELECT * FROM ai_sample_label
            WHERE sample_id = #{sampleId} AND horizon_trading_days = #{horizonDays}
              AND label_status = 'MATURED' AND is_current = 1
            ORDER BY revision_no DESC, created_at DESC LIMIT 1
            """)
    AiSampleLabel selectForReview(
            @Param("sampleId") Long sampleId,
            @Param("horizonDays") Integer horizonDays
    );

    @Select("""
            <script>
            SELECT * FROM ai_sample_label
            WHERE label_version = #{labelVersion}
              AND label_status = 'MATURED'
              AND is_current = 1
              AND sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                #{sampleId}
              </foreach>
            ORDER BY sample_id, horizon_trading_days
            </script>
            """)
    List<AiSampleLabel> selectMaturedForSamples(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("labelVersion") String labelVersion
    );

    @Select("""
            <script>
            SELECT * FROM ai_sample_label
            WHERE label_version = #{labelVersion}
              AND is_current = 1
              AND sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                #{sampleId}
              </foreach>
            ORDER BY sample_id, horizon_trading_days
            </script>
            """)
    List<AiSampleLabel> selectForSamplesAndVersion(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("labelVersion") String labelVersion
    );

    @Select("""
            <script>
            SELECT * FROM ai_sample_label
            WHERE label_version = #{labelVersion}
              AND is_current = 1
              AND sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                #{sampleId}
              </foreach>
            ORDER BY sample_id, horizon_trading_days
            FOR UPDATE
            </script>
            """)
    List<AiSampleLabel> selectCurrentForSamplesAndVersionForUpdate(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("labelVersion") String labelVersion
    );
}
