package com.maogou.stock.domain.entity.research;

/** Coverage for one versioned factor definition in a historical dataset. */
public class AiHistoricalReadinessFeatureMetric {
    public String factorCode;
    public Integer totalCount;
    public Integer readyCount;
    public Integer missingCount;
}
