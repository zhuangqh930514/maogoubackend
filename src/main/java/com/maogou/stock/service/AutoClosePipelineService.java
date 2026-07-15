package com.maogou.stock.service;

public interface AutoClosePipelineService {
    void runEnabledPipelines();

    void retryWaitingPipelines();

    void runCurrentUserNow();
}
