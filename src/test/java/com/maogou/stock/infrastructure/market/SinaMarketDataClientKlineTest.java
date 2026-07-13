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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinaMarketDataClientKlineTest {

    @Test
    void pointInTimeKlineUsesUnadjustedVersionedDataAndCutsFutureBars() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).contains("/api/qt/stock/kline/get");
                    assertThat(request.getURI().getQuery()).contains("fqt=0");
                    assertThat(request.getURI().getQuery()).contains("end=20260710");
                })
                .andRespond(withSuccess("""
                        {"data":{"klines":[
                          "2026-07-09,10,10,11,9,100,1000",
                          "2026-07-10,10,11,12,9,120,1300",
                          "2026-07-13,11,99,100,10,150,1600"
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        KlineSeriesSnapshot snapshot = client.fetchKlineAt(
                "600519", "day", 100, LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(snapshot.adjustmentMode()).isEqualTo("NONE");
        assertThat(snapshot.source()).isEqualTo("EASTMONEY");
        assertThat(snapshot.asOfTime()).isEqualTo(LocalDateTime.of(2026, 7, 10, 16, 0));
        assertThat(snapshot.sourceFingerprint()).hasSize(64);
        assertThat(snapshot.points()).extracting(point -> point.tradeDate())
                .containsExactly(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10));
        server.verify();
    }
}
