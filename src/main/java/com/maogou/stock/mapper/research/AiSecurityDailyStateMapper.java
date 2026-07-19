package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface AiSecurityDailyStateMapper extends BaseMapper<AiSecurityDailyState> {

    @Insert("""
            INSERT INTO ai_security_daily_state (
                stock_code, trade_date, source_batch_id, source_revision, revision_no, is_current,
                supersedes_state_id, listed_on, listed_days, security_status, st_status, is_st,
                suspended, limit_ratio, limit_up_price, limit_down_price, is_limit_up, is_limit_down,
                buy_tradable, sell_tradable, quality_status, missing_reason, evidence_json,
                source_fingerprint, observed_at, created_at
            ) VALUES (
                #{item.stockCode}, #{item.tradeDate}, #{item.sourceBatchId}, #{item.sourceRevision},
                #{item.revisionNo}, #{item.isCurrent}, #{item.supersedesStateId}, #{item.listedOn},
                #{item.listedDays}, #{item.securityStatus}, #{item.stStatus}, #{item.isSt}, #{item.suspended},
                #{item.limitRatio}, #{item.limitUpPrice}, #{item.limitDownPrice}, #{item.isLimitUp},
                #{item.isLimitDown}, #{item.buyTradable}, #{item.sellTradable}, #{item.qualityStatus},
                #{item.missingReason}, #{item.evidenceJson}, #{item.sourceFingerprint}, #{item.observedAt},
                #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiSecurityDailyState item);

    @Select("""
            SELECT * FROM ai_security_daily_state
            WHERE stock_code = #{stockCode} AND trade_date = #{tradeDate} AND is_current = 1
            FOR UPDATE
            """)
    AiSecurityDailyState selectCurrentForUpdate(
            @Param("stockCode") String stockCode,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_security_daily_state
            WHERE stock_code = #{stockCode} AND trade_date = #{tradeDate} AND is_current = 1
            LIMIT 1
            """)
    AiSecurityDailyState selectCurrent(
            @Param("stockCode") String stockCode,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Update("UPDATE ai_security_daily_state SET is_current = 0 WHERE id = #{id} AND is_current = 1")
    int markSuperseded(@Param("id") Long id);

    @Select("""
            <script>
            SELECT * FROM ai_security_daily_state FORCE INDEX (idx_security_daily_state_stock_date)
            WHERE is_current = 1
              AND stock_code IN
              <foreach collection="stockCodes" item="stockCode" open="(" separator="," close=")">
                #{stockCode}
              </foreach>
              AND trade_date BETWEEN #{startDate} AND #{endDate}
            ORDER BY stock_code, trade_date
            </script>
            """)
    List<AiSecurityDailyState> selectCurrentForStocksBetween(
            @Param("stockCodes") List<String> stockCodes,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
