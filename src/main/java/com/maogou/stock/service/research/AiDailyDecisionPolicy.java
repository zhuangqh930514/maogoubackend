package com.maogou.stock.service.research;

import java.math.BigDecimal;

public interface AiDailyDecisionPolicy {

    String version();

    Decision decide(Input input);

    record Input(
            BigDecimal t1Signal,
            BigDecimal t2Signal,
            BigDecimal t3Signal,
            BigDecimal factorOosReliability,
            BigDecimal strategyOosValidation,
            BigDecimal dataQuality,
            BigDecimal riskScore,
            int outOfSampleCount,
            boolean hardStop,
            String predictionAction,
            boolean holding,
            String unavailableReason,
            BigDecimal llmConfidence,
            String reportAction
    ) {
        public Input withLlmConfidence(BigDecimal value) {
            return new Input(t1Signal, t2Signal, t3Signal, factorOosReliability,
                    strategyOosValidation, dataQuality, riskScore, outOfSampleCount,
                    hardStop, predictionAction, holding, unavailableReason, value, reportAction);
        }

        public Input withRiskScore(BigDecimal value) {
            return new Input(t1Signal, t2Signal, t3Signal, factorOosReliability,
                    strategyOosValidation, dataQuality, value, outOfSampleCount,
                    hardStop, predictionAction, holding, unavailableReason, llmConfidence, reportAction);
        }

        public Input withReportAction(String value) {
            return new Input(t1Signal, t2Signal, t3Signal, factorOosReliability,
                    strategyOosValidation, dataQuality, riskScore, outOfSampleCount,
                    hardStop, predictionAction, holding, unavailableReason, llmConfidence, value);
        }
    }

    record Decision(
            String category,
            BigDecimal systemScore,
            BigDecimal horizonSignalScore,
            BigDecimal factorReliabilityScore,
            BigDecimal strategyValidationScore,
            BigDecimal dataQualityComponent,
            BigDecimal riskComponent,
            String finalAction,
            BigDecimal riskScore,
            String riskLevel,
            String confidenceLevel,
            String unavailableReason
    ) {
    }
}
