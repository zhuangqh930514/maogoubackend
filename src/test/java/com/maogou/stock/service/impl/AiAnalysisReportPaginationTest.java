package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisReportPaginationTest {

    @Test
    void defaultsToLatestAvailableDateAndReturnsRequestedPage() {
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        when(reportMapper.selectObjs(any())).thenReturn(List.of(LocalDate.of(2026, 7, 13)));
        when(reportMapper.selectCount(any())).thenReturn(23L);
        when(reportMapper.selectList(any())).thenReturn(List.of(report(20L), report(19L)));

        var response = AuthContext.callAs(5L, () -> service(reportMapper)
                .pageReports(null, null, 2, 10, "ALL"));

        assertThat(response.selectedDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(response.latestAvailableDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(23);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.items()).extracting("id").containsExactly(20L, 19L);

        ArgumentCaptor<Wrapper<AiAnalysisReport>> listQuery = wrapperCaptor();
        verify(reportMapper).selectList(listQuery.capture());
        assertThat(listQuery.getValue().getCustomSqlSegment())
                .contains("report_date")
                .contains("LIMIT 10 OFFSET 10");
    }

    @Test
    void usesExplicitDateFilterAndClampsPageAfterFiltering() {
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        when(reportMapper.selectCount(any())).thenReturn(3L);
        when(reportMapper.selectList(any())).thenReturn(List.of(report(3L)));

        var response = AuthContext.callAs(5L, () -> service(reportMapper)
                .pageReports("600519", LocalDate.of(2026, 7, 12), 99, 10, "HIGH_RISK"));

        assertThat(response.selectedDate()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        verify(reportMapper, never()).selectObjs(any());

        ArgumentCaptor<Wrapper<AiAnalysisReport>> listQuery = wrapperCaptor();
        verify(reportMapper).selectList(listQuery.capture());
        assertThat(listQuery.getValue().getCustomSqlSegment())
                .contains("stock_code")
                .contains("score")
                .contains("LIMIT 10 OFFSET 0");
    }

    @Test
    void returnsEmptyPageWhenUserHasNoReports() {
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        when(reportMapper.selectObjs(any())).thenReturn(List.of());

        var response = AuthContext.callAs(5L, () -> service(reportMapper)
                .pageReports(null, null, 1, 10, "ALL"));

        assertThat(response.selectedDate()).isNull();
        assertThat(response.latestAvailableDate()).isNull();
        assertThat(response.total()).isZero();
        assertThat(response.items()).isEmpty();
        verify(reportMapper, never()).selectCount(any());
        verify(reportMapper, never()).selectList(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<AiAnalysisReport>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static AiAnalysisReport report(long id) {
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = id;
        report.userId = 5L;
        report.stockCode = "600519";
        report.stockName = "贵州茅台";
        report.score = 72;
        report.advice = "谨慎观察";
        report.reportDate = LocalDate.of(2026, 7, 13);
        report.generatedAt = LocalDateTime.of(2026, 7, 13, 16, 30);
        return report;
    }

    private static AiAnalysisServiceImpl service(AiAnalysisReportMapper reportMapper) {
        return new AiAnalysisServiceImpl(
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
                null);
    }
}
