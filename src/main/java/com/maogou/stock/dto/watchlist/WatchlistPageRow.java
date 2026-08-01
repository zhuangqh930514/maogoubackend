package com.maogou.stock.dto.watchlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Database projection used by filtered/sorted watchlist pages. */
public class WatchlistPageRow {
    public Long id;
    public String stockCode;
    public String stockName;
    public String market;
    public String groupName;
    public Integer priority;
    public Integer pinned;
    public BigDecimal price;
    public BigDecimal percent;
    public BigDecimal volumeRatio;
    public String quoteStatus;
    public String quoteSource;
    public LocalDateTime quoteAsOf;
    public Integer aiScore;
    public String finalAction;
    public String category;
    public BigDecimal riskScore;
}
