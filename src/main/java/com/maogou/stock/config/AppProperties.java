package com.maogou.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
