package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EastMoneyMarketDataClientTest {

    @Test
    void buildsAStableCurrentListedCohortAcrossTheFourABoards() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        expectCatalog(server, "m:0+t:6", "000001", "平安银行", "19910403");
        expectCatalog(server, "m:0+t:80", "300750", "宁德时代", "20180611");
        expectCatalog(server, "m:1+t:2", "600519", "贵州茅台", "20010827");
        expectCatalog(server, "m:1+t:23", "688981", "中芯国际", "20200716");
        EastMoneyMarketDataClient client = new EastMoneyMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules());

        HistoricalMarketDataProvider.UniverseCatalog catalog = client.fetchCurrentListedUniverse(
                4, LocalDateTime.of(2026, 7, 16, 18, 0));

        assertThat(catalog.securities()).hasSize(4);
        assertThat(catalog.securities()).extracting(HistoricalMarketDataProvider.Security::stockCode)
                .containsExactlyInAnyOrder("000001", "300750", "600519", "688981");
        assertThat(catalog.securities()).extracting(HistoricalMarketDataProvider.Security::market)
                .containsExactlyInAnyOrder("SZ", "SZ", "SH", "SH");
        assertThat(catalog.sourceFingerprint()).hasSize(64);
        server.verify();
    }

    @Test
    void fetchesPointInTimeForwardAdjustedDailyKline() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> {
                    assertThat(request.getURI().getQuery()).contains("secid=1.600519");
                    assertThat(request.getURI().getQuery()).contains("fqt=1");
                    assertThat(request.getURI().getQuery()).contains("end=20260716");
                })
                .andRespond(withSuccess("""
                        {"data":{"klines":[
                          "2026-07-15,1400.00,1410.00,1420.00,1390.00,1000,141000000",
                          "2026-07-16,1410.00,1430.00,1440.00,1405.00,1200,171600000"
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        EastMoneyMarketDataClient client = new EastMoneyMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules());

        var result = client.fetchHistoricalKline(
                "600519", 120, LocalDateTime.of(2026, 7, 16, 16, 0), "QFQ");

        assertThat(result.adjustmentMode()).isEqualTo("QFQ");
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(1).volume()).isEqualTo(120_000L);
        assertThat(result.fingerprintMatches()).isTrue();
        server.verify();
    }

    @Test
    void normalizesNumericBoardCodeAndPrefersLevelTwoIndustry() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("code=SH600519"))
                .andRespond(withSuccess("""
                        {"ssbk":[
                          {"BOARD_CODE":"438","BOARD_NAME":"食品饮料","BOARD_RANK":1},
                          {"BOARD_CODE":"1277","BOARD_NAME":"白酒Ⅱ","BOARD_RANK":2},
                          {"BOARD_CODE":"1575","BOARD_NAME":"白酒Ⅲ","BOARD_RANK":3}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        EastMoneyMarketDataClient client = new EastMoneyMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules());

        ResearchMarketDataProvider.IndustryMembershipData membership = client.fetchIndustryAt(
                "600519", LocalDateTime.of(2026, 7, 14, 16, 0));

        assertThat(membership.industryCode()).isEqualTo("BK1277");
        assertThat(membership.industryName()).isEqualTo("白酒Ⅱ");
        assertThat(membership.sourceFingerprint()).hasSize(64);
        server.verify();
    }

    private static void expectCatalog(
            MockRestServiceServer server,
            String filter,
            String code,
            String name,
            String listedOn
    ) {
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("fs=" + filter))
                .andRespond(withSuccess("""
                        {"data":{"diff":[{"f12":"%s","f14":"%s","f13":0,"f26":"%s"}]}}
                        """.formatted(code, name, listedOn), MediaType.APPLICATION_JSON));
    }
}
