package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiTradePlanReview;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiTradePlanReviewMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiConditionalTradeStrategyServiceImplTest {

    @Test
    void failedReportDoesNotCreateReviewsThatCanNeverMature() {
        AiTradePlanReviewMapper reviewMapper = mock(AiTradePlanReviewMapper.class);
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 9L;
        report.status = AnalysisStatus.FAILED;
        AiConditionalStrategyPayload payload = new AiConditionalStrategyPayload(
                "TEST", LocalDate.of(2026, 7, 14), LocalDateTime.of(2026, 7, 14, 16, 0),
                null, null, null, List.of(), List.of(), List.of(), null, null, List.of());

        service(reviewMapper, mock(AiAnalysisReportMapper.class)).initializeReviews(report, payload);

        verifyNoInteractions(reviewMapper);
    }

    @Test
    void verificationReadsOnlyMaturePendingReviewQueueBeforeLoadingReports() {
        AiTradePlanReviewMapper reviewMapper = mock(AiTradePlanReviewMapper.class);
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        when(reviewMapper.selectList(any())).thenReturn(List.of());

        var result = service(reviewMapper, reportMapper).verifyMatured(5L, LocalDate.of(2026, 7, 14));

        assertThat(result.processedCount()).isZero();
        verifyNoInteractions(reportMapper);
        ArgumentCaptor<Wrapper<AiTradePlanReview>> captor = wrapperCaptor();
        verify(reviewMapper).selectList(captor.capture());
        QueryWrapper<AiTradePlanReview> query = (QueryWrapper<AiTradePlanReview>) captor.getValue();
        assertThat(query.getSqlSegment()).contains("status", "outcome_trade_date");
        assertThat(query.getParamNameValuePairs().values())
                .contains("PENDING", LocalDate.of(2026, 7, 14));
    }

    private static AiConditionalTradeStrategyServiceImpl service(
            AiTradePlanReviewMapper reviewMapper,
            AiAnalysisReportMapper reportMapper
    ) {
        return new AiConditionalTradeStrategyServiceImpl(
                null,
                reviewMapper,
                null,
                reportMapper,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper().findAndRegisterModules());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<AiTradePlanReview>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
