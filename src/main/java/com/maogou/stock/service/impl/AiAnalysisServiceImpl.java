package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.WatchlistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final AiAnalysisReportMapper reportMapper;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final ModelConfigService modelConfigService;
    private final LocalAiClient localAiClient;

    public AiAnalysisServiceImpl(
            AiAnalysisReportMapper reportMapper,
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            ModelConfigService modelConfigService,
            LocalAiClient localAiClient
    ) {
        this.reportMapper = reportMapper;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.modelConfigService = modelConfigService;
        this.localAiClient = localAiClient;
    }

    @Override
    public List<AiAnalysisReportResponse> listReports(String code) {
        QueryWrapper<AiAnalysisReport> wrapper = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("generated_at");
        if (code != null && !code.isBlank()) {
            wrapper.eq("stock_code", code);
        }
        return reportMapper.selectList(wrapper).stream().map(AiAnalysisReportResponse::from).toList();
    }

    @Override
    @Transactional
    public AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh) {
        StockDetailResponse detail = marketDataService.stockDetail(code);
        AiModelConfig config = modelConfigService.currentEntity();
        String prompt = buildPrompt(detail, config);
        AiAnalysisReport report = new AiAnalysisReport();
        report.userId = AuthContext.currentUserIdOrDefault();
        report.stockCode = detail.quote().code();
        report.stockName = detail.quote().name();
        report.rawPrompt = prompt;
        report.generatedAt = LocalDateTime.now();
        report.createdAt = report.generatedAt;
        report.updatedAt = report.generatedAt;
        report.deleted = 0;
        report.status = AnalysisStatus.PENDING;
        report.score = detail.aiScore();
        report.advice = detail.aiAdvice();
        report.promptSummary = "实时价 " + detail.quote().price() + "，涨跌幅 " + detail.quote().percent() + "%，量比 " + detail.quote().volumeRatio();

        try {
            String aiText = localAiClient.chat(prompt, config);
            report.rawResponse = aiText;
            report.technicalAnalysis = aiText.isBlank() ? "模型返回为空，等待重新生成。" : aiText;
            report.riskWarning = "请结合仓位、成交量和市场整体风险复核，不应将模型输出作为唯一交易依据。";
            report.buySellPoints = "等待关键均线和成交量确认后分批处理，单笔交易需预设止损。";
            report.status = AnalysisStatus.SUCCESS;
        } catch (Exception ex) {
            report.technicalAnalysis = "AI 分析调用失败，已保存失败报告供排查。";
            report.riskWarning = "本次报告未获得模型推理结果。";
            report.buySellPoints = "暂不生成买卖点。";
            report.errorMessage = ex.getMessage();
            report.status = AnalysisStatus.FAILED;
        }
        reportMapper.insert(report);
        return AiAnalysisReportResponse.from(report);
    }

    @Override
    public void analyzeWatchlist() {
        for (WatchStockResponse stock : watchlistService.list("全部")) {
            analyzeStock(stock.code(), false);
        }
    }

    private String buildPrompt(StockDetailResponse detail, AiModelConfig config) {
        String template = config.promptTemplate == null || config.promptTemplate.isBlank()
                ? "请基于行情、K线、财务数据输出技术面分析、风险提示、建议买卖点和评分。"
                : config.promptTemplate;
        return """
                %s

                股票：%s %s
                实时行情：价格=%s，涨跌幅=%s%%，量比=%s
                财务摘要：PE=%s，PB=%s，营收同比=%s%%，净利同比=%s%%
                近K线样本：%s
                输出要求：包含 technicalAnalysis、riskWarning、buySellPoints、score，避免保证收益。
                """.formatted(
                template,
                detail.quote().name(),
                detail.quote().code(),
                detail.quote().price(),
                detail.quote().percent(),
                detail.quote().volumeRatio(),
                detail.finance().pe(),
                detail.finance().pb(),
                detail.finance().revenueGrowth(),
                detail.finance().profitGrowth(),
                detail.kline().stream().limit(10).toList()
        );
    }
}
