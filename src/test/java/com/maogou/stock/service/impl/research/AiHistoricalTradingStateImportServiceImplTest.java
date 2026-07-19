package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.AiHistoricalTradingStateImportService;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSecurityDailyStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalTradingStateImportServiceImplTest {

    @Test
    void importsVerifiedStateAsARevisionOfExistingKlineEvidence() {
        AiSecurityDailyStateMapper mapper = mock(AiSecurityDailyStateMapper.class);
        AiSecurityDailyStateService stateService = mock(AiSecurityDailyStateService.class);
        AiSecurityDailyState partial = partialState();
        when(mapper.selectCurrent("600519", LocalDate.of(2026, 7, 17))).thenReturn(partial);
        AiHistoricalTradingStateImportService service = service(mapper, stateService);

        AiHistoricalTradingStateImportService.ImportResult result = service.importCsv(request(validFile(
                validRow("0,0,1,1"))));

        assertThat(result.parsedRows()).isEqualTo(1);
        assertThat(result.insertedRevisions()).isEqualTo(1);
        assertThat(result.earliestTradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(result.insertedUniverseSnapshots()).isEqualTo(1);
        ArgumentCaptor<AiSecurityDailyStateService.StateCommand> command = ArgumentCaptor.forClass(
                AiSecurityDailyStateService.StateCommand.class);
        verify(stateService).store(command.capture());
        assertThat(command.getValue().qualityStatus()).isEqualTo("READY");
        assertThat(command.getValue().stStatus()).isEqualTo("VERIFIED_NON_ST");
        assertThat(command.getValue().buyTradable()).isEqualTo(1);
        assertThat(command.getValue().sellTradable()).isEqualTo(1);
        assertThat(command.getValue().sourceFingerprint()).hasSize(64);
        assertThat(command.getValue().evidenceJson()).contains("TUSHARE", "fileChecksum", "previousClose");
        assertThat(command.getValue().evidenceJson()).contains("801120.SI", "食品饮料", "SW2021");
    }

    @Test
    void acceptsACompleteVendorArchiveWhenRawKlineArchiveIsNotInTheLocalSnapshot() {
        AiSecurityDailyStateMapper mapper = mock(AiSecurityDailyStateMapper.class);
        AiSecurityDailyStateService stateService = mock(AiSecurityDailyStateService.class);
        AiHistoricalTradingStateImportService service = service(mapper, stateService);

        AiHistoricalTradingStateImportService.ImportResult result = service.importCsv(request(validFile(
                validRow("0,0,1,1"))));

        assertThat(result.insertedRevisions()).isEqualTo(1);
        ArgumentCaptor<AiSecurityDailyStateService.StateCommand> command = ArgumentCaptor.forClass(
                AiSecurityDailyStateService.StateCommand.class);
        verify(stateService).store(command.capture());
        assertThat(command.getValue().sourceBatchId()).isNull();
        assertThat(command.getValue().qualityStatus()).isEqualTo("READY");
    }

    @Test
    void rejectsRowsThatClaimASealedLimitUpCanBeBought() {
        AiSecurityDailyStateMapper mapper = mock(AiSecurityDailyStateMapper.class);
        AiSecurityDailyStateService stateService = mock(AiSecurityDailyStateService.class);
        AiSecurityDailyState partial = partialState();
        partial.evidenceJson = "{\"previousClose\":10.00,\"currentOpen\":11.00,\"currentClose\":11.00}";
        when(mapper.selectCurrent("600519", LocalDate.of(2026, 7, 17))).thenReturn(partial);
        AiHistoricalTradingStateImportService service = service(mapper, stateService);

        assertThatThrownBy(() -> service.importCsv(request(validFile(
                validRow("1,0,1,1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一字涨停不能买入");
        verify(stateService, never()).store(any());
    }

    private static AiHistoricalTradingStateImportService service(
            AiSecurityDailyStateMapper mapper,
            AiSecurityDailyStateService stateService
    ) {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setHistoricalStateImportMaxBytes(1024 * 1024);
        AiResearchUniverseService universeService = mock(AiResearchUniverseService.class);
        when(universeService.createSystemCoreSnapshot(any())).thenAnswer(invocation -> {
            AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
            snapshot.id = 12L;
            snapshot.status = "FINALIZED";
            snapshot.qualityStatus = "PARTIAL";
            snapshot.pointInTimeStatus = "READY";
            return new AiResearchUniverseService.SnapshotResult(null, snapshot, List.of(), false);
        });
        return new AiHistoricalTradingStateImportServiceImpl(
                properties, mapper, stateService, universeService,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiHistoricalTradingStateImportService.ImportRequest request(MockMultipartFile file) {
        return new AiHistoricalTradingStateImportService.ImportRequest(
                file, "TUSHARE", "20260719", LocalDateTime.of(2026, 7, 19, 9, 0), 5L);
    }

    private static MockMultipartFile validFile(String row) {
        String body = AiHistoricalTradingStateImportServiceImpl.HEADER + "\n" + row + "\n";
        return new MockMultipartFile("file", "state.csv", "text/csv", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String validRow(String executionFlags) {
        return "600519,贵州茅台,SH,2026-07-17,2001-08-27,,LISTED,0,0,0.10,"
                + executionFlags
                + ",801120.SI,食品饮料,SW2021,2021-12-13,,"
                + "TUSHARE/index_member/801120.SI,TUSHARE/namechange+suspend_d+daily";
    }

    private static AiSecurityDailyState partialState() {
        AiSecurityDailyState state = new AiSecurityDailyState();
        state.id = 8L;
        state.stockCode = "600519";
        state.tradeDate = LocalDate.of(2026, 7, 17);
        state.sourceBatchId = 16L;
        state.listedOn = LocalDate.of(2001, 8, 27);
        state.sourceFingerprint = "kline-partial-state";
        state.evidenceJson = "{\"previousClose\":10.00,\"currentOpen\":10.30,\"currentClose\":10.40}";
        state.limitRatio = new BigDecimal("0.10");
        return state;
    }
}
