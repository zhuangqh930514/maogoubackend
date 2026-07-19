package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiTrainingReadinessMetric;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiTrainingReadinessService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiTrainingReadinessServiceImpl implements AiTrainingReadinessService {

    private final AiTrainingDatasetItemMapper itemMapper;
    private final AiTrainingReadinessGate gate;

    public AiTrainingReadinessServiceImpl(AiTrainingDatasetItemMapper itemMapper) {
        this.itemMapper = itemMapper;
        this.gate = new AiTrainingReadinessGate();
    }

    @Override
    public AiTrainingReadinessGate.Readiness assess(LocalDateTime asOfTime) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("训练就绪度缺少截止时间");
        }
        List<AiTrainingReadinessMetric> metrics = itemMapper.selectTrainingReadinessMetrics(
                AiResearchContract.LABEL_VERSION, asOfTime);
        int tradingDays = 0;
        int stocks = 0;
        Map<Integer, Integer> labels = new LinkedHashMap<>();
        Map<String, Integer> regimes = new LinkedHashMap<>();
        int tradabilityEligibleLabels = 0;
        int readyTradabilityLabels = 0;
        int universeEligibleLabels = 0;
        int readyUniverseLabels = 0;
        int sectorEligibleLabels = 0;
        int readySectorLabels = 0;
        if (metrics != null) {
            for (AiTrainingReadinessMetric metric : metrics) {
                if (metric == null || metric.dimensionType == null || metric.dimensionKey == null) {
                    continue;
                }
                int count = Math.max(0, metric.metricCount == null ? 0 : metric.metricCount);
                switch (metric.dimensionType) {
                    case "TRADING_DAYS" -> tradingDays = count;
                    case "STOCKS" -> stocks = count;
                    case "HORIZON" -> labels.put(Integer.parseInt(metric.dimensionKey), count);
                    case "REGIME" -> regimes.put(metric.dimensionKey, count);
                    case "TRADABILITY_STATE" -> {
                        if ("ELIGIBLE".equals(metric.dimensionKey)) {
                            tradabilityEligibleLabels = count;
                        } else if ("READY".equals(metric.dimensionKey)) {
                            readyTradabilityLabels = count;
                        }
                    }
                    case "UNIVERSE_MEMBERSHIP" -> {
                        if ("ELIGIBLE".equals(metric.dimensionKey)) {
                            universeEligibleLabels = count;
                        } else if ("READY".equals(metric.dimensionKey)) {
                            readyUniverseLabels = count;
                        }
                    }
                    case "SECTOR_EVIDENCE" -> {
                        if ("ELIGIBLE".equals(metric.dimensionKey)) {
                            sectorEligibleLabels = count;
                        } else if ("READY".equals(metric.dimensionKey)) {
                            readySectorLabels = count;
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return gate.evaluate(new AiTrainingReadinessGate.Evidence(
                tradingDays, stocks, labels, regimes, tradabilityEligibleLabels, readyTradabilityLabels,
                universeEligibleLabels, readyUniverseLabels, sectorEligibleLabels, readySectorLabels));
    }
}
