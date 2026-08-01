package com.maogou.stock.service.research;

import java.math.BigDecimal;

public record HorizonDecisionEvidence(
        int horizonDays,
        BigDecimal rawPredictionSignal,
        int evaluatedCount,
        BigDecimal hitRate,
        BigDecimal wilsonLowerBound,
        String evidenceScope
) {
    public HorizonDecisionEvidence {
        if (horizonDays != 1 && horizonDays != 2 && horizonDays != 3) {
            throw new IllegalArgumentException("V2 只支持 T+1/T+2/T+3");
        }
        evaluatedCount = Math.max(0, evaluatedCount);
    }
}
