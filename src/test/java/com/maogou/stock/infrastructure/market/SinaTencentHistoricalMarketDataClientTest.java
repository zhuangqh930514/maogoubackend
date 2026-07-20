package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinaTencentHistoricalMarketDataClientTest {

    @Test
    void buildsCurrentListedCatalogFromSinaBoardPages() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(ExpectedCount.manyTimes(), request -> {
                    assertThat(request.getURI().getHost()).isEqualTo("vip.stock.finance.sina.com.cn");
                    assertThat(request.getURI().getQuery()).contains("num=100");
                })
                .andRespond(withSuccess("""
                        [
                          {"symbol":"sz000001","code":"000001","name":"平安银行","trade":"10.00","settlement":"9.90"},
                          {"symbol":"sh600519","code":"600519","name":"贵州茅台","trade":"1400.00","settlement":"1390.00"},
                          {"symbol":"sh600001","code":"600001","name":"邯郸钢铁","trade":"0.00","settlement":"0.00"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        SinaTencentHistoricalMarketDataClient client = new SinaTencentHistoricalMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules());

        HistoricalMarketDataProvider.UniverseCatalog catalog = client.fetchCurrentListedUniverse(
                2, LocalDateTime.of(2026, 7, 16, 18, 0));

        assertThat(catalog.providerCode()).isEqualTo("SINA_TENCENT");
        assertThat(catalog.securities()).hasSize(2);
        assertThat(catalog.securities()).extracting(HistoricalMarketDataProvider.Security::stockName)
                .containsExactlyInAnyOrder("平安银行", "贵州茅台");
        assertThat(catalog.securities()).extracting(HistoricalMarketDataProvider.Security::stockCode)
                .doesNotContain("600001");
        assertThat(catalog.securities()).allSatisfy(security -> assertThat(security.listedOn()).isNull());
        assertThat(catalog.sourceFingerprint()).hasSize(64);
        server.verify();
    }

    @Test
    void fetchesTencentForwardAdjustedPointInTimeKline() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> {
                    assertThat(request.getURI().getHost()).isEqualTo("web.ifzq.gtimg.cn");
                    assertThat(request.getURI().getQuery()).contains("sh600519,day");
                    assertThat(request.getURI().getQuery()).contains("2026-07-16,120,qfq");
                })
                .andRespond(withSuccess("""
                        {"code":0,"data":{"sh600519":{"qfqday":[
                          ["2026-07-15","1400.00","1410.00","1420.00","1390.00","1000.000"],
                          ["2026-07-16","1410.00","1430.00","1440.00","1405.00","1200.000"]
                        ]}}}
                        """, MediaType.APPLICATION_JSON));
        SinaTencentHistoricalMarketDataClient client = new SinaTencentHistoricalMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules());

        var series = client.fetchHistoricalKline(
                "600519", 120, LocalDateTime.of(2026, 7, 16, 16, 0), "QFQ");

        assertThat(series.source()).isEqualTo("TENCENT");
        assertThat(series.adjustmentMode()).isEqualTo("QFQ");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(1).volume()).isEqualTo(120_000L);
        assertThat(series.fingerprintMatches()).isTrue();
        server.verify();
    }
}
