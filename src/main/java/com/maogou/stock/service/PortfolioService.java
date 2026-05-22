package com.maogou.stock.service;

import com.maogou.stock.dto.portfolio.PortfolioSummaryResponse;
import com.maogou.stock.dto.portfolio.TradeRecordCreateRequest;
import com.maogou.stock.dto.portfolio.TradeRecordResponse;

import java.util.List;

public interface PortfolioService {
    List<TradeRecordResponse> trades();

    TradeRecordResponse addBuyRecord(TradeRecordCreateRequest request);

    PortfolioSummaryResponse portfolio();
}
