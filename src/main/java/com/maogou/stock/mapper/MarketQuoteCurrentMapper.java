package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.MarketQuoteCurrent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MarketQuoteCurrentMapper extends BaseMapper<MarketQuoteCurrent> {
    @Insert("""
            INSERT INTO market_quote_current
                (symbol, name, market, latest_price, change_amount, change_percent, volume_ratio,
                 amount, trade_date, source_provider, source_as_of, source_fingerprint, source_status,
                 data_mode, updated_at)
            VALUES
                (#{symbol}, #{name}, #{market}, #{latestPrice}, #{changeAmount}, #{changePercent}, #{volumeRatio},
                 #{amount}, #{tradeDate}, #{sourceProvider}, #{sourceAsOf}, #{sourceFingerprint}, #{sourceStatus},
                 #{dataMode}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                name = IF(VALUES(source_as_of) >= source_as_of, VALUES(name), name),
                market = IF(VALUES(source_as_of) >= source_as_of, VALUES(market), market),
                latest_price = IF(VALUES(source_as_of) >= source_as_of, VALUES(latest_price), latest_price),
                change_amount = IF(VALUES(source_as_of) >= source_as_of, VALUES(change_amount), change_amount),
                change_percent = IF(VALUES(source_as_of) >= source_as_of, VALUES(change_percent), change_percent),
                volume_ratio = IF(VALUES(source_as_of) >= source_as_of, VALUES(volume_ratio), volume_ratio),
                amount = IF(VALUES(source_as_of) >= source_as_of, VALUES(amount), amount),
                trade_date = IF(VALUES(source_as_of) >= source_as_of, VALUES(trade_date), trade_date),
                source_provider = IF(VALUES(source_as_of) >= source_as_of, VALUES(source_provider), source_provider),
                source_as_of = IF(VALUES(source_as_of) >= source_as_of, VALUES(source_as_of), source_as_of),
                source_fingerprint = IF(VALUES(source_as_of) >= source_as_of, VALUES(source_fingerprint), source_fingerprint),
                source_status = IF(VALUES(source_as_of) >= source_as_of, VALUES(source_status), source_status),
                data_mode = IF(VALUES(source_as_of) >= source_as_of, VALUES(data_mode), data_mode),
                updated_at = IF(VALUES(source_as_of) >= source_as_of, VALUES(updated_at), updated_at)
            """)
    int upsert(MarketQuoteCurrent current);

    @Select("""
            <script>
            SELECT * FROM market_quote_current
            WHERE symbol IN
            <foreach collection="symbols" item="symbol" open="(" separator="," close=")">
                #{symbol}
            </foreach>
            </script>
            """)
    List<MarketQuoteCurrent> selectBySymbols(@Param("symbols") List<String> symbols);
}
