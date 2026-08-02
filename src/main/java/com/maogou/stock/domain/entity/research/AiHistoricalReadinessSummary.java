package com.maogou.stock.domain.entity.research;

import java.math.BigDecimal;

/** Scalar readiness facts calculated from the immutable research chain. */
public class AiHistoricalReadinessSummary {
    public Integer tradingDays;
    public Integer stockCount;
    public Integer tradabilityEligible;
    public Integer tradabilityReady;
    public Integer universeEligible;
    public Integer universeReady;
    public Integer sectorEligible;
    public Integer sectorReady;
    public Integer leakageViolationCount;
    public Integer duplicateCount;
    public Integer mockSourceCount;
    public Integer staleSourceCount;
    public Integer inferredFactCount;

    public BigDecimal tradabilityCoverage;
    public BigDecimal universeCoverage;
    public BigDecimal sectorCoverage;
}
