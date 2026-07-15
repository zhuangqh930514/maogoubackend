package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TradePositionAggregate {
    public String stockCode;
    public String stockName;
    public BigDecimal totalCost;
    public Integer quantity;
    public LocalDateTime lastTradedAt;
}
