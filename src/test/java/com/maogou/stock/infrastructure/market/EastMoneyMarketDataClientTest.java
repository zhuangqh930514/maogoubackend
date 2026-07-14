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
}
