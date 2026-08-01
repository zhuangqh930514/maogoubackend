package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;

public class PositionSnapshotSummary {
    public BigDecimal totalCost;
    public BigDecimal totalMarketValue;
    public BigDecimal totalUnrealizedPnl;
    public BigDecimal totalTodayPnl;
    public long positionTotal;
    public long pricedPositionCount;
    public long unpricedPositionCount;
}
