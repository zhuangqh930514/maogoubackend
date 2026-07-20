package com.maogou.stock.domain.entity.research;

import java.time.LocalDateTime;

/**
 * Business lineage used to resolve a local dataset item against production facts.
 * Local sample and label ids are intentionally excluded from this contract.
 */
public class AiTrainingDatasetImportLineage {
    public String featureFingerprint;
    public String labelFingerprint;
    public String universeFingerprint;
    public String tradingStateFingerprint;
    public String sectorMembershipFingerprint;
    public LocalDateTime sampleAsOfTime;
    public LocalDateTime labelAvailableAt;
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public Integer horizonTradingDays;
}
