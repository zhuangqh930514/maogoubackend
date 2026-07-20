package com.maogou.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QqOrderDocumentSyncServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAnAtomicSnapshotFromThePublicQqDocumentResponse() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setQqOrderSyncSourceUrl(
                "https://docs.qq.com/sheet/DRExLaU9LY0hKckN5?tab=BB08J2");
        Path output = temporaryDirectory.resolve("orders.json");
        properties.getScheduler().setQqOrderSyncOutputFile(output.toString());

        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), request -> {
                    assertThat(request.getURI().getHost()).isEqualTo("docs.qq.com");
                    assertThat(request.getURI().getPath()).isEqualTo("/dop-api/opendoc");
                    assertThat(request.getURI().getQuery()).contains("id=DRExLaU9LY0hKckN5", "tab=BB08J2");
                })
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header(HttpHeaders.REFERER,
                        "https://docs.qq.com/sheet/DRExLaU9LY0hKckN5?tab=BB08J2"))
                .andRespond(withSuccess(
                        "clientVarsCallback({\"bodyData\":{\"initialTitle\":\"溯博订单\",\"lastSaveTimestamp\":\"2026年07月19日 23:06\"}})",
                        new MediaType("application", "javascript", StandardCharsets.UTF_8)));

        QqOrderDocumentSyncService service = new QqOrderDocumentSyncService(
                properties,
                restTemplate,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));

        QqOrderDocumentSyncService.SyncResult result = service.sync();

        server.verify();
        assertThat(result.output()).isEqualTo(output.toAbsolutePath());
        assertThat(result.title()).isEqualTo("溯博订单");
        JsonNode snapshot = new ObjectMapper().readTree(output.toFile());
        assertThat(snapshot.path("syncedAt").asText()).isEqualTo("2026-07-20T00:00:00Z");
        assertThat(snapshot.path("documentId").asText()).isEqualTo("DRExLaU9LY0hKckN5");
        assertThat(snapshot.path("sheetId").asText()).isEqualTo("BB08J2");
        assertThat(snapshot.path("document").path("bodyData").path("initialTitle").asText())
                .isEqualTo("溯博订单");
    }
}
