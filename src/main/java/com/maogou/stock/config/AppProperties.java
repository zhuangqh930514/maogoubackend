package com.maogou.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "maogou")
public class AppProperties {

    private final Market market = new Market();
    private final Ai ai = new Ai();
    private final Scheduler scheduler = new Scheduler();
    private final Auth auth = new Auth();
    private final WebSearch webSearch = new WebSearch();

    public Market getMarket() {
        return market;
    }

    public Ai getAi() {
        return ai;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Auth getAuth() {
        return auth;
    }

    public WebSearch getWebSearch() {
        return webSearch;
    }

    public static class Market {
        private String provider = "sina";
        private String akshareBaseUrl = "http://127.0.0.1:5000";
        private String sinaBaseUrl = "https://hq.sinajs.cn";
        private int timeoutMs = 5000;
        private long quoteCacheTtlSeconds = 15;
        private long financeCacheTtlSeconds = 1800;
        private long sectorHeatmapCacheTtlSeconds = 60;
        private long sectorHotStocksCacheTtlSeconds = 60;
        private String researchProviderOrder = "EASTMONEY,SINA";
        private String benchmarkSymbol = "000300.SH";
        private long sourceCooldownBaseSeconds = 30;
        private long sourceCooldownMaxSeconds = 900;
        private int sourceCooldownFailureThreshold = 3;
        private BigDecimal sourcePriceTolerancePct = new BigDecimal("0.005");
        private BigDecimal sourceVolumeTolerancePct = new BigDecimal("0.15");
        private BigDecimal sourceMaxDailyChangePct = new BigDecimal("0.35");
        private int newsFeatureWindowHours = 36;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getAkshareBaseUrl() {
            return akshareBaseUrl;
        }

        public void setAkshareBaseUrl(String akshareBaseUrl) {
            this.akshareBaseUrl = akshareBaseUrl;
        }

        public String getSinaBaseUrl() {
            return sinaBaseUrl;
        }

        public void setSinaBaseUrl(String sinaBaseUrl) {
            this.sinaBaseUrl = sinaBaseUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getQuoteCacheTtlSeconds() {
            return quoteCacheTtlSeconds;
        }

        public void setQuoteCacheTtlSeconds(long quoteCacheTtlSeconds) {
            this.quoteCacheTtlSeconds = quoteCacheTtlSeconds;
        }

        public long getFinanceCacheTtlSeconds() {
            return financeCacheTtlSeconds;
        }

        public void setFinanceCacheTtlSeconds(long financeCacheTtlSeconds) {
            this.financeCacheTtlSeconds = financeCacheTtlSeconds;
        }

        public long getSectorHeatmapCacheTtlSeconds() {
            return sectorHeatmapCacheTtlSeconds;
        }

        public void setSectorHeatmapCacheTtlSeconds(long sectorHeatmapCacheTtlSeconds) {
            this.sectorHeatmapCacheTtlSeconds = sectorHeatmapCacheTtlSeconds;
        }

        public long getSectorHotStocksCacheTtlSeconds() {
            return sectorHotStocksCacheTtlSeconds;
        }

        public void setSectorHotStocksCacheTtlSeconds(long sectorHotStocksCacheTtlSeconds) {
            this.sectorHotStocksCacheTtlSeconds = sectorHotStocksCacheTtlSeconds;
        }

        public String getResearchProviderOrder() {
            return researchProviderOrder;
        }

        public void setResearchProviderOrder(String researchProviderOrder) {
            this.researchProviderOrder = researchProviderOrder;
        }

        public String getBenchmarkSymbol() {
            return benchmarkSymbol;
        }

        public void setBenchmarkSymbol(String benchmarkSymbol) {
            this.benchmarkSymbol = benchmarkSymbol;
        }

        public long getSourceCooldownBaseSeconds() {
            return sourceCooldownBaseSeconds;
        }

        public void setSourceCooldownBaseSeconds(long sourceCooldownBaseSeconds) {
            this.sourceCooldownBaseSeconds = sourceCooldownBaseSeconds;
        }

        public long getSourceCooldownMaxSeconds() {
            return sourceCooldownMaxSeconds;
        }

        public void setSourceCooldownMaxSeconds(long sourceCooldownMaxSeconds) {
            this.sourceCooldownMaxSeconds = sourceCooldownMaxSeconds;
        }

        public int getSourceCooldownFailureThreshold() {
            return sourceCooldownFailureThreshold;
        }

        public void setSourceCooldownFailureThreshold(int sourceCooldownFailureThreshold) {
            this.sourceCooldownFailureThreshold = sourceCooldownFailureThreshold;
        }

        public BigDecimal getSourcePriceTolerancePct() {
            return sourcePriceTolerancePct;
        }

        public void setSourcePriceTolerancePct(BigDecimal sourcePriceTolerancePct) {
            this.sourcePriceTolerancePct = sourcePriceTolerancePct;
        }

        public BigDecimal getSourceVolumeTolerancePct() {
            return sourceVolumeTolerancePct;
        }

        public void setSourceVolumeTolerancePct(BigDecimal sourceVolumeTolerancePct) {
            this.sourceVolumeTolerancePct = sourceVolumeTolerancePct;
        }

        public BigDecimal getSourceMaxDailyChangePct() {
            return sourceMaxDailyChangePct;
        }

        public void setSourceMaxDailyChangePct(BigDecimal sourceMaxDailyChangePct) {
            this.sourceMaxDailyChangePct = sourceMaxDailyChangePct;
        }

        public int getNewsFeatureWindowHours() {
            return newsFeatureWindowHours;
        }

        public void setNewsFeatureWindowHours(int newsFeatureWindowHours) {
            this.newsFeatureWindowHours = newsFeatureWindowHours;
        }
    }

    public static class Ai {
        private String apiBaseUrl = "http://localhost:11434/v1";
        private String modelName = "qwen3.6";
        private String apiKey = "";
        private int timeoutMs = 60000;
        private double temperature = 0.2;
        private int maxTokens = 2048;

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Scheduler {
        private boolean enabled = false;
        private long newsFixedRateMs = 300000;
        private long intradayAnalysisFixedRateMs = 1800000;
        private String closeAnalysisCron = "0 30 15 * * MON-FRI";
        private String evolutionReviewCron = "0 10 16 * * MON-FRI";
        private String autoClosePipelineCron = "0 0 16 * * MON-FRI";
        private String weeklyEvolutionCron = "0 0 18 * * FRI";
        private String monthlyTrainingCron = "0 0 19 1 * *";
        private boolean qqOrderSyncEnabled = true;
        private String qqOrderSyncCron = "0 0 0 * * *";
        private String qqOrderSyncSourceUrl = "https://docs.qq.com/sheet/DRExLaU9LY0hKckN5?tab=BB08J2";
        private String qqOrderSyncOutputFile = "./data/external-orders/tencent-doc-orders.json";
        private int weeklyLookbackDays = 180;
        private int monthlyMinimumSamples = 1000;
        private String trainingArtifactRoot = "./data/ai-training";
        private long modelPackageMaxBytes = 536870912L;
        private long historicalStateImportMaxBytes = 134217728L;
        private String trainerPythonExecutable = "python3";
        private String trainerScript = "ml/train_ranker.py";
        private long trainerTimeoutSeconds = 1800;
        private String trainingExecutionMode = "MODEL_PACKAGE_ONLY";
        private double modelMinimumTestRocAuc = 0.52d;
        private String tradingHolidays = "2026-01-01,2026-01-02,2026-02-16,2026-02-17,2026-02-18,2026-02-19,2026-02-20,2026-02-23,2026-04-06,2026-05-01,2026-05-04,2026-05-05,2026-06-19,2026-09-25,2026-10-01,2026-10-02,2026-10-05,2026-10-06,2026-10-07";
        private String tradingWorkdays = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getNewsFixedRateMs() {
            return newsFixedRateMs;
        }

        public void setNewsFixedRateMs(long newsFixedRateMs) {
            this.newsFixedRateMs = newsFixedRateMs;
        }

        public long getIntradayAnalysisFixedRateMs() {
            return intradayAnalysisFixedRateMs;
        }

        public void setIntradayAnalysisFixedRateMs(long intradayAnalysisFixedRateMs) {
            this.intradayAnalysisFixedRateMs = intradayAnalysisFixedRateMs;
        }

        public String getCloseAnalysisCron() {
            return closeAnalysisCron;
        }

        public void setCloseAnalysisCron(String closeAnalysisCron) {
            this.closeAnalysisCron = closeAnalysisCron;
        }

        public String getEvolutionReviewCron() {
            return evolutionReviewCron;
        }

        public void setEvolutionReviewCron(String evolutionReviewCron) {
            this.evolutionReviewCron = evolutionReviewCron;
        }

        public String getAutoClosePipelineCron() {
            return autoClosePipelineCron;
        }

        public void setAutoClosePipelineCron(String autoClosePipelineCron) {
            this.autoClosePipelineCron = autoClosePipelineCron;
        }

        public String getWeeklyEvolutionCron() {
            return weeklyEvolutionCron;
        }

        public void setWeeklyEvolutionCron(String weeklyEvolutionCron) {
            this.weeklyEvolutionCron = weeklyEvolutionCron;
        }

        public String getMonthlyTrainingCron() {
            return monthlyTrainingCron;
        }

        public void setMonthlyTrainingCron(String monthlyTrainingCron) {
            this.monthlyTrainingCron = monthlyTrainingCron;
        }

        public boolean isQqOrderSyncEnabled() {
            return qqOrderSyncEnabled;
        }

        public void setQqOrderSyncEnabled(boolean qqOrderSyncEnabled) {
            this.qqOrderSyncEnabled = qqOrderSyncEnabled;
        }

        public String getQqOrderSyncCron() {
            return qqOrderSyncCron;
        }

        public void setQqOrderSyncCron(String qqOrderSyncCron) {
            this.qqOrderSyncCron = qqOrderSyncCron;
        }

        public String getQqOrderSyncSourceUrl() {
            return qqOrderSyncSourceUrl;
        }

        public void setQqOrderSyncSourceUrl(String qqOrderSyncSourceUrl) {
            this.qqOrderSyncSourceUrl = qqOrderSyncSourceUrl;
        }

        public String getQqOrderSyncOutputFile() {
            return qqOrderSyncOutputFile;
        }

        public void setQqOrderSyncOutputFile(String qqOrderSyncOutputFile) {
            this.qqOrderSyncOutputFile = qqOrderSyncOutputFile;
        }

        public int getWeeklyLookbackDays() {
            return weeklyLookbackDays;
        }

        public void setWeeklyLookbackDays(int weeklyLookbackDays) {
            this.weeklyLookbackDays = weeklyLookbackDays;
        }

        public int getMonthlyMinimumSamples() {
            return monthlyMinimumSamples;
        }

        public void setMonthlyMinimumSamples(int monthlyMinimumSamples) {
            this.monthlyMinimumSamples = monthlyMinimumSamples;
        }

        public String getTrainingArtifactRoot() {
            return trainingArtifactRoot;
        }

        public void setTrainingArtifactRoot(String trainingArtifactRoot) {
            this.trainingArtifactRoot = trainingArtifactRoot;
        }

        public long getModelPackageMaxBytes() {
            return modelPackageMaxBytes;
        }

        public void setModelPackageMaxBytes(long modelPackageMaxBytes) {
            this.modelPackageMaxBytes = modelPackageMaxBytes;
        }

        public long getHistoricalStateImportMaxBytes() {
            return historicalStateImportMaxBytes;
        }

        public void setHistoricalStateImportMaxBytes(long historicalStateImportMaxBytes) {
            this.historicalStateImportMaxBytes = historicalStateImportMaxBytes;
        }

        public String getTrainerPythonExecutable() {
            return trainerPythonExecutable;
        }

        public void setTrainerPythonExecutable(String trainerPythonExecutable) {
            this.trainerPythonExecutable = trainerPythonExecutable;
        }

        public String getTrainerScript() {
            return trainerScript;
        }

        public void setTrainerScript(String trainerScript) {
            this.trainerScript = trainerScript;
        }

        public long getTrainerTimeoutSeconds() {
            return trainerTimeoutSeconds;
        }

        public void setTrainerTimeoutSeconds(long trainerTimeoutSeconds) {
            this.trainerTimeoutSeconds = trainerTimeoutSeconds;
        }

        public String getTrainingExecutionMode() {
            return trainingExecutionMode;
        }

        public void setTrainingExecutionMode(String trainingExecutionMode) {
            this.trainingExecutionMode = trainingExecutionMode;
        }

        public double getModelMinimumTestRocAuc() {
            return modelMinimumTestRocAuc;
        }

        public void setModelMinimumTestRocAuc(double modelMinimumTestRocAuc) {
            this.modelMinimumTestRocAuc = modelMinimumTestRocAuc;
        }

        public String getTradingHolidays() {
            return tradingHolidays;
        }

        public void setTradingHolidays(String tradingHolidays) {
            this.tradingHolidays = tradingHolidays;
        }

        public String getTradingWorkdays() {
            return tradingWorkdays;
        }

        public void setTradingWorkdays(String tradingWorkdays) {
            this.tradingWorkdays = tradingWorkdays;
        }
    }

    public static class Auth {
        private String jwtSecret = "maogou-local-dev-secret-change-me";
        private long accessTokenTtlMinutes = 1440;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public long getAccessTokenTtlMinutes() {
            return accessTokenTtlMinutes;
        }

        public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
            this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        }
    }

    public static class WebSearch {
        private boolean enabled = true;
        private String provider = "duckduckgo";
        private String duckDuckGoHtmlUrl = "https://html.duckduckgo.com/html/";
        private int timeoutMs = 8000;
        private int maxResults = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getDuckDuckGoHtmlUrl() {
            return duckDuckGoHtmlUrl;
        }

        public void setDuckDuckGoHtmlUrl(String duckDuckGoHtmlUrl) {
            this.duckDuckGoHtmlUrl = duckDuckGoHtmlUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }
}
