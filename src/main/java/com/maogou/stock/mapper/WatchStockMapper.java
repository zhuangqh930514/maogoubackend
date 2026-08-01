package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.watchlist.WatchlistPageRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WatchStockMapper extends BaseMapper<WatchStock> {
    @Select("""
            SELECT id, user_id, stock_code, stock_name, market, group_name, priority, pinned, deleted, created_at, updated_at
            FROM watch_stock
            WHERE user_id = #{userId} AND stock_code = #{code}
            LIMIT 1
            """)
    WatchStock selectAnyByUserIdAndCode(@Param("userId") Long userId, @Param("code") String code);

    @Update("""
            UPDATE watch_stock
            SET stock_name = #{stockName},
                market = #{market},
                group_name = #{groupName},
                priority = #{priority},
                deleted = 0,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int restore(WatchStock entity);

    @Update("""
            UPDATE watch_stock
            SET priority = #{priority}, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND stock_code = #{stockCode} AND deleted = 0
            """)
    int updatePriority(WatchStock entity);

    @Update("""
            UPDATE watch_stock
            SET pinned = #{pinned}, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND stock_code = #{stockCode} AND deleted = 0
            """)
    int updatePinned(WatchStock entity);

    @Select("""
            SELECT MIN(priority)
            FROM watch_stock
            WHERE user_id = #{userId} AND deleted = 0
            """)
    Integer selectMinPriorityByUserId(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT w.id)
            FROM watch_stock w
            LEFT JOIN market_quote_current q ON q.symbol = w.stock_code
            LEFT JOIN ai_daily_decision_item d ON d.user_id = w.user_id AND d.stock_code = w.stock_code
                AND d.decision_snapshot_id = (
                    SELECT s.id FROM ai_daily_decision_snapshot s
                    WHERE s.user_id = w.user_id AND s.is_current = 1
                    ORDER BY s.trade_date DESC, s.snapshot_version DESC, s.id DESC LIMIT 1)
            WHERE w.user_id = #{userId} AND w.deleted = 0
            <if test="pinnedOnly">AND w.pinned = 1</if>
            <if test="keyword != null and keyword != ''">
                AND (w.stock_code LIKE CONCAT('%', #{keyword}, '%')
                     OR w.stock_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <choose>
                <when test="view == 'AI重点'">AND d.system_score &gt;= 78</when>
                <when test="view == '高波动'">AND q.volume_ratio &gt;= 1.8</when>
                <when test="view == '稳健持有'">AND d.final_action = 'HOLD'</when>
            </choose>
            </script>
            """)
    long countFilteredPage(
            @Param("userId") Long userId,
            @Param("view") String view,
            @Param("keyword") String keyword,
            @Param("pinnedOnly") boolean pinnedOnly
    );

    @Select("""
            <script>
            SELECT w.id, w.stock_code, w.stock_name, w.market, w.group_name, w.priority, w.pinned,
                   q.latest_price AS price, q.change_percent AS percent, q.volume_ratio AS volume_ratio,
                   q.source_status AS quote_status, q.source_provider AS quote_source,
                   q.source_as_of AS quote_as_of,
                   d.system_score AS ai_score, d.final_action, d.category, d.risk_score
            FROM watch_stock w
            LEFT JOIN market_quote_current q ON q.symbol = w.stock_code
            LEFT JOIN ai_daily_decision_item d ON d.user_id = w.user_id AND d.stock_code = w.stock_code
                AND d.decision_snapshot_id = (
                    SELECT s.id FROM ai_daily_decision_snapshot s
                    WHERE s.user_id = w.user_id AND s.is_current = 1
                    ORDER BY s.trade_date DESC, s.snapshot_version DESC, s.id DESC LIMIT 1)
            WHERE w.user_id = #{userId} AND w.deleted = 0
            <if test="pinnedOnly">AND w.pinned = 1</if>
            <if test="keyword != null and keyword != ''">
                AND (w.stock_code LIKE CONCAT('%', #{keyword}, '%')
                     OR w.stock_name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <choose>
                <when test="view == 'AI重点'">AND d.system_score &gt;= 78</when>
                <when test="view == '高波动'">AND q.volume_ratio &gt;= 1.8</when>
                <when test="view == '稳健持有'">AND d.final_action = 'HOLD'</when>
            </choose>
            ORDER BY w.pinned DESC,
            <choose>
                <when test="sort == 'AI_SCORE_DESC'">d.system_score DESC</when>
                <when test="sort == 'PERCENT_DESC'">q.change_percent DESC</when>
                <when test="sort == 'PERCENT_ASC'">q.change_percent ASC</when>
                <when test="sort == 'VOLUME_RATIO_DESC'">q.volume_ratio DESC</when>
                <otherwise>w.priority ASC</otherwise>
            </choose>,
            w.priority ASC, w.created_at DESC, w.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<WatchlistPageRow> selectFilteredPage(
            @Param("userId") Long userId,
            @Param("view") String view,
            @Param("keyword") String keyword,
            @Param("sort") String sort,
            @Param("pinnedOnly") boolean pinnedOnly,
            @Param("limit") int limit,
            @Param("offset") long offset
    );
}
