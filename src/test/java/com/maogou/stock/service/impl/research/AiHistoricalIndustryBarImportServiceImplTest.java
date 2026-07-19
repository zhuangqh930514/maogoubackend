package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import com.maogou.stock.mapper.research.AiIndustryDailyBarMapper;
import com.maogou.stock.service.research.AiHistoricalIndustryBarImportService;
import com.maogou.stock.service.research.AiIndustryDailyBarService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalIndustryBarImportServiceImplTest {

    @Test
    void importsAuditableIndustryBarsThroughImmutableService() {
        AiIndustryDailyBarMapper mapper = mock(AiIndustryDailyBarMapper.class);
        AiIndustryDailyBarService barService = mock(AiIndustryDailyBarService.class);
        AiIndustryDailyBar stored = new AiIndustryDailyBar();
        stored.id = 1L;
        when(barService.store(any())).thenReturn(stored);
        AiHistoricalIndustryBarImportService service = service(mapper, barService);

        AiHistoricalIndustryBarImportService.ImportResult result = service.importCsv(request(file(
                "801120.SI,食品饮料,SW2021,2026-07-17,100,103,98,101,1000,2000,TUSHARE/sw_daily/801120.SI")));

        assertThat(result.parsedRows()).isEqualTo(1);
        assertThat(result.industryCount()).isEqualTo(1);
        assertThat(result.insertedRevisions()).isEqualTo(1);
        assertThat(result.earliestTradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        ArgumentCaptor<AiIndustryDailyBarService.BarCommand> captor = ArgumentCaptor.forClass(
                AiIndustryDailyBarService.BarCommand.class);
        verify(barService).store(captor.capture());
        assertThat(captor.getValue().classificationStandard()).isEqualTo("SW2021");
        assertThat(captor.getValue().sourceFingerprint()).hasSize(64);
        assertThat(captor.getValue().evidenceJson()).contains("fileChecksum", "TUSHARE/sw_daily");
    }

    @Test
    void rejectsMockSourcesAndImpossibleBars() {
        AiHistoricalIndustryBarImportService service = service(
                mock(AiIndustryDailyBarMapper.class), mock(AiIndustryDailyBarService.class));
        AiHistoricalIndustryBarImportService.ImportRequest mockRequest = new AiHistoricalIndustryBarImportService.ImportRequest(
                file("801120.SI,食品饮料,SW2021,2026-07-17,100,103,98,101,1000,2000,MOCK"),
                "MOCK", "v1", LocalDateTime.of(2026, 7, 19, 9, 0), 5L);
        assertThatThrownBy(() -> service.importCsv(mockRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源名称不合法");
        assertThatThrownBy(() -> service.importCsv(request(file(
                "801120.SI,食品饮料,SW2021,2026-07-17,100,99,98,101,1000,2000,TUSHARE/sw_daily"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OHLC");
    }

    private static AiHistoricalIndustryBarImportService service(
            AiIndustryDailyBarMapper mapper,
            AiIndustryDailyBarService barService
    ) {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setHistoricalStateImportMaxBytes(1024 * 1024);
        return new AiHistoricalIndustryBarImportServiceImpl(
                properties, mapper, barService, new ObjectMapper().findAndRegisterModules());
    }

    private static AiHistoricalIndustryBarImportService.ImportRequest request(MockMultipartFile file) {
        return new AiHistoricalIndustryBarImportService.ImportRequest(
                file, "TUSHARE", "20260719", LocalDateTime.of(2026, 7, 19, 9, 0), 5L);
    }

    private static MockMultipartFile file(String row) {
        String body = AiHistoricalIndustryBarImportServiceImpl.HEADER + "\n" + row + "\n";
        return new MockMultipartFile("file", "industry.csv", "text/csv",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
