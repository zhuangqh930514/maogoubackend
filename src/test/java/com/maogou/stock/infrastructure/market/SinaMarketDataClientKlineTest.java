package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinaMarketDataClientKlineTest {

    @Test
    void pointInTimeKlineUsesTheIndependentSinaSourceAndCutsFutureBars() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).contains("/api/json_v2.php/CN_MarketDataService.getKLineData");
                    assertThat(request.getURI().getQuery()).contains("symbol=sh600519");
                    assertThat(request.getURI().getQuery()).contains("scale=240");
                })
                .andRespond(withSuccess("""
                        [
                          {"day":"2026-07-09","open":"10","close":"10","low":"9","high":"11","volume":"10000"},
                          {"day":"2026-07-10","open":"10","close":"11","low":"9","high":"12","volume":"12000"},
                          {"day":"2026-07-13","open":"11","close":"99","low":"10","high":"100","volume":"15000"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        KlineSeriesSnapshot snapshot = client.fetchKlineAt(
                "600519", "day", 100, LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(snapshot.adjustmentMode()).isEqualTo("NONE");
        assertThat(snapshot.source()).isEqualTo("SINA");
        assertThat(snapshot.asOfTime()).isEqualTo(LocalDateTime.of(2026, 7, 10, 16, 0));
        assertThat(snapshot.sourceFingerprint()).hasSize(64);
        assertThat(snapshot.points()).extracting(point -> point.tradeDate())
                .containsExactly(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10));
        server.verify();
    }

    @Test
    void pointInTimeKlineFallsBackToJsonpWithSinaGuardPrefix() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath())
                        .contains("/api/json_v2.php/CN_MarketDataService.getKLineData"))
                .andRespond(withSuccess("<html>temporary guard page</html>", MediaType.TEXT_HTML));
        server.expect(request -> assertThat(request.getURI().getPath())
                        .contains("/api/json_v2.php/CN_MarketDataService.getKLineData"))
                .andRespond(withSuccess("<html>temporary guard page</html>", MediaType.TEXT_HTML));
        server.expect(request -> assertThat(request.getURI().getPath())
                        .contains("/api/jsonp_v2.php/var _sh600519_240="))
                .andRespond(withSuccess("""
                        /*<script>location.href='//sina.com';</script>*/
                        var _sh600519_240=([
                          {"day":"2026-07-09","open":"10","close":"10","low":"9","high":"11","volume":"10000"},
                          {"day":"2026-07-10","open":"10","close":"11","low":"9","high":"12","volume":"12000"}
                        ]);
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        KlineSeriesSnapshot snapshot = client.fetchKlineAt(
                "600519", "day", 100, LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(snapshot.points()).extracting(point -> point.tradeDate())
                .containsExactly(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10));
        server.verify();
    }

    @Test
    void pointInTimeKlineRetriesARejectedJsonPayloadBeforeFallingBack() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath())
                        .contains("/api/json_v2.php/CN_MarketDataService.getKLineData"))
                .andRespond(withSuccess("<html>temporary guard page</html>", MediaType.TEXT_HTML));
        server.expect(request -> assertThat(request.getURI().getPath())
                        .contains("/api/json_v2.php/CN_MarketDataService.getKLineData"))
                .andRespond(withSuccess("""
                        [{"day":"2026-07-10","open":"10","close":"11","low":"9","high":"12","volume":"12000"}]
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        KlineSeriesSnapshot snapshot = client.fetchKlineAt(
                "600519", "day", 100, LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(snapshot.points()).extracting(point -> point.tradeDate())
                .containsExactly(LocalDate.of(2026, 7, 10));
        server.verify();
    }

    @Test
    void pointInTimeKlineReportsBothSinaChannelFailures() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        for (int attempt = 0; attempt < 4; attempt++) {
            server.expect(request -> assertThat(request.getURI().getHost()).isEqualTo("quotes.sina.cn"))
                    .andRespond(withSuccess("<html>temporary guard page</html>", MediaType.TEXT_HTML));
        }
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        assertThatThrownBy(() -> client.fetchKlineAt(
                "600519", "day", 100, LocalDateTime.of(2026, 7, 10, 16, 0)))
                .hasMessageContaining("JSON通道失败")
                .hasMessageContaining("JSONP通道失败")
                .hasMessageContaining("返回格式异常");
        server.verify();
    }
}
