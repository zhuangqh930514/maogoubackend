package com.maogou.stock.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinaMarketDataClientFinanceTest {

    @Test
    void preservesFinancePointInTimeMetadataAndMissingFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/api/qt/stock/get"))
                .andRespond(withSuccess("""
                        {"data":{"f116":2100000000000,"f117":2100000000000,"f162":2840,"f167":930,"f152":2}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/ZYZBAjaxNew"))
                .andRespond(withSuccess("""
                        {"data":[{
                          "REPORT_DATE":"2026-03-31 00:00:00",
                          "NOTICE_DATE":"2026-04-25 18:00:00",
                          "EPSJB":"49.3",
                          "BPS":"216.3",
                          "TOTALOPERATEREVE":"54702912385",
                          "TOTALOPERATEREVETZ":"16.8",
                          "PARENTNETPROFIT":"27242512886",
                          "PARENTNETPROFITTZ":"15.4",
                          "ROEJQ":"10.57",
                          "XSMLL":"-",
                          "XSJLL":"52.22",
                          "ZCFZL":"12.12",
                          "MGJYXJJE":"21.49"
                        }]}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        FinanceSnapshotResponse finance = client.fetchFinance("600519");

        assertThat(finance.reportDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(finance.publishedAt()).isEqualTo(LocalDateTime.of(2026, 4, 25, 18, 0));
        assertThat(finance.fetchedAt()).isNotNull();
        assertThat(finance.source()).isEqualTo("EASTMONEY");
        assertThat(finance.grossMargin()).isNull();
        assertThat(finance.netMargin()).isEqualByComparingTo("52.22");
        server.verify();
    }

    @Test
    void historicalFinanceSelectsTheLatestReportPublishedByTheRequestedTime() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/ZYZBAjaxNew"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"REPORT_DATE":"2026-06-30 00:00:00","NOTICE_DATE":"2026-07-30 18:00:00","ROEJQ":"12.0"},
                          {"REPORT_DATE":"2026-03-31 00:00:00","NOTICE_DATE":"2026-04-25 18:00:00","ROEJQ":"10.0"},
                          {"REPORT_DATE":"2025-12-31 00:00:00","NOTICE_DATE":"2026-03-20 18:00:00","ROEJQ":"8.0"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        FinanceSnapshotResponse finance = client.fetchFinanceAt(
                "600519", LocalDateTime.of(2026, 5, 1, 16, 0));

        assertThat(finance.reportDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(finance.publishedAt()).isEqualTo(LocalDateTime.of(2026, 4, 25, 18, 0));
        assertThat(finance.roe()).isEqualByComparingTo("10.0");
        assertThat(finance.pe()).isNull();
        assertThat(finance.source()).isEqualTo("EASTMONEY_PIT");
        server.verify();
    }

    @Test
    void historicalFinanceRejectsARevisionPublishedAfterTheRequestedTime() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/ZYZBAjaxNew"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"REPORT_DATE":"2026-03-31 00:00:00","NOTICE_DATE":"2026-04-25 18:00:00","UPDATE_DATE":"2026-06-01 10:00:00","ROEJQ":"99.0"},
                          {"REPORT_DATE":"2025-12-31 00:00:00","NOTICE_DATE":"2026-03-20 18:00:00","UPDATE_DATE":"2026-03-20 18:00:00","ROEJQ":"8.0"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        FinanceSnapshotResponse finance = client.fetchFinanceAt(
                "600519", LocalDateTime.of(2026, 5, 1, 16, 0));

        assertThat(finance.reportDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(finance.roe()).isEqualByComparingTo("8.0");
        server.verify();
    }

    @Test
    void historicalFinancePrefersTheNewestReportPeriodOverALateOlderCorrection() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/ZYZBAjaxNew"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"REPORT_DATE":"2026-03-31 00:00:00","NOTICE_DATE":"2026-04-25 18:00:00","ROEJQ":"10.0"},
                          {"REPORT_DATE":"2025-12-31 00:00:00","NOTICE_DATE":"2026-04-30 18:00:00","ROEJQ":"8.0"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        FinanceSnapshotResponse finance = client.fetchFinanceAt(
                "600519", LocalDateTime.of(2026, 5, 1, 16, 0));

        assertThat(finance.reportDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        server.verify();
    }

    @Test
    void pointInTimeFinanceNeverMixesARequestTimeValuationQuote() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertThat(request.getURI().getPath()).contains("/ZYZBAjaxNew"))
                .andRespond(withSuccess("""
                        {"data":[{"REPORT_DATE":"2026-03-31 00:00:00","NOTICE_DATE":"2026-04-25 18:00:00","ROEJQ":"10.0"}]}
                        """, MediaType.APPLICATION_JSON));
        SinaMarketDataClient client = new SinaMarketDataClient(
                restTemplate, new ObjectMapper().findAndRegisterModules(), new AppProperties());

        FinanceSnapshotResponse finance = client.fetchFinanceAt("600519", LocalDateTime.now());

        assertThat(finance.pe()).isNull();
        assertThat(finance.pb()).isNull();
        assertThat(finance.source()).isEqualTo("EASTMONEY_PIT");
        server.verify();
    }
}
