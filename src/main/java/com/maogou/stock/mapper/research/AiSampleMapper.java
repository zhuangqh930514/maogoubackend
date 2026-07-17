package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiSample;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiSampleMapper extends BaseMapper<AiSample> {

    @Select("""
            SELECT MAX(trade_date)
            FROM ai_sample
            WHERE trade_date BETWEEN #{startDate} AND #{endDate}
              AND as_of_time <= #{asOfTime}
            """)
    LocalDate selectLatestResearchTradeDate(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT DISTINCT market_regime
            FROM ai_sample
            WHERE trade_date BETWEEN #{startDate} AND #{endDate}
              AND as_of_time <= #{asOfTime}
              AND market_regime IS NOT NULL
              AND market_regime <> ''
            ORDER BY market_regime
            """)
    List<String> selectResearchMarketRegimes(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            <script>
            SELECT s.*
            FROM ai_sample s
            INNER JOIN (
                SELECT stock_code, MAX(as_of_time) AS max_as_of_time
                FROM ai_sample
                WHERE data_batch_id = #{dataBatchId}
                  AND trade_date = #{tradeDate}
                  AND stock_code IN
                  <foreach collection="stockCodes" item="stockCode" open="(" separator="," close=")">
                    #{stockCode}
                  </foreach>
                GROUP BY stock_code
            ) latest ON latest.stock_code = s.stock_code AND latest.max_as_of_time = s.as_of_time
            WHERE s.data_batch_id = #{dataBatchId} AND s.trade_date = #{tradeDate}
            ORDER BY s.stock_code
            </script>
            """)
    List<AiSample> selectLatestForDecision(
            @Param("dataBatchId") Long dataBatchId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("stockCodes") List<String> stockCodes
    );

    @Select("""
            SELECT * FROM ai_sample
            WHERE stock_code = #{stockCode} AND trade_date <= #{tradeDate}
              AND sample_phase = 'AFTER_CLOSE'
            ORDER BY trade_date DESC, as_of_time DESC LIMIT 1
            """)
    AiSample selectLatestForAnalysis(
            @Param("stockCode") String stockCode,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT s.id, s.stock_code, s.trade_date, s.tradable_status, s.source_fingerprint
            FROM ai_sample s FORCE INDEX (idx_sample_pending_labels)
            WHERE s.trade_date < #{tradeDate}
              AND s.quality_status IN ('READY', 'PARTIAL')
              AND (
                  SELECT COUNT(*)
                  FROM ai_sample_label l
                  WHERE l.sample_id = s.id
                    AND l.label_version = #{labelVersion}
              ) < 4
            ORDER BY s.trade_date, s.stock_code, s.id
            LIMIT #{limit}
            """)
    List<AiSample> selectPendingLabelCandidates(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("labelVersion") String labelVersion,
            @Param("limit") int limit
    );
}
