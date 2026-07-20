package com.maogou.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maogou.stock.config.AppProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class QqOrderDocumentSyncService {

    private static final String QQ_DOCS_HOST = "docs.qq.com";
    private static final String JSONP_PREFIX = "clientVarsCallback(";
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QqOrderDocumentSyncService(
            AppProperties properties,
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        this(properties, restTemplate, objectMapper, Clock.systemUTC());
    }

    QqOrderDocumentSyncService(
            AppProperties properties,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SyncResult sync() {
        SourceDocument source = SourceDocument.from(properties.getScheduler().getQqOrderSyncSourceUrl());
        JsonNode document = readDocument(source);
        JsonNode bodyData = document.path("bodyData");
        String title = requiredText(bodyData, "initialTitle");
        String lastSaveTimestamp = bodyData.path("lastSaveTimestamp").asText("");

        Path output = Path.of(properties.getScheduler().getQqOrderSyncOutputFile())
                .toAbsolutePath()
                .normalize();
        writeSnapshot(output, source, document, title, lastSaveTimestamp);

        return new SyncResult(output, title, lastSaveTimestamp);
    }

    private JsonNode readDocument(SourceDocument source) {
        URI endpoint = UriComponentsBuilder.fromUriString("https://docs.qq.com/dop-api/opendoc")
                .queryParam("tab", source.sheetId())
                .queryParam("u", "")
                .queryParam("noEscape", "1")
                .queryParam("enableSmartsheetSplit", "1")
                .queryParam("startrow", "0")
                .queryParam("endrow", "255")
                .queryParam("needSheetState", "1")
                .queryParam("sliceStates", "1")
                .queryParam("block_end_col", "31")
                .queryParam("block_end_row", "255")
                .queryParam("block_start_col", "0")
                .queryParam("block_start_row", "0")
                .queryParam("id", source.documentId())
                .queryParam("normal", "1")
                .queryParam("outformat", "1")
                .queryParam("wb", "1")
                .queryParam("nowb", "0")
                .queryParam("callback", "clientVarsCallback")
                .queryParam("xsrf", "")
                .build(true)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT);
        headers.set(HttpHeaders.REFERER, source.sourceUrl().toString());
        headers.setAccept(java.util.List.of(MediaType.valueOf("application/javascript"), MediaType.ALL));
        ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("腾讯文档返回为空。");
        }

        String json = unwrapJsonp(body);
        try {
            JsonNode document = objectMapper.readTree(json);
            if (!document.path("bodyData").isObject()) {
                throw new IllegalStateException("腾讯文档响应缺少 bodyData。");
            }
            return document;
        } catch (IOException exception) {
            throw new IllegalStateException("腾讯文档响应无法解析。", exception);
        }
    }

    private void writeSnapshot(
            Path output,
            SourceDocument source,
            JsonNode document,
            String title,
            String lastSaveTimestamp
    ) {
        Path parent = Objects.requireNonNull(output.getParent(), "同步输出路径必须包含目录。");
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("schemaVersion", 1);
            snapshot.put("syncedAt", Instant.now(clock).toString());
            snapshot.put("sourceUrl", source.sourceUrl().toString());
            snapshot.put("documentId", source.documentId());
            snapshot.put("sheetId", source.sheetId());
            snapshot.put("title", title);
            snapshot.put("lastSaveTimestamp", lastSaveTimestamp);
            snapshot.set("document", document);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            moveAtomically(temporary, output);
        } catch (IOException exception) {
            throw new IllegalStateException("腾讯文档快照写入失败：" + output, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The next run uses a unique temporary file and does not depend on cleanup here.
                }
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String unwrapJsonp(String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith(JSONP_PREFIX) || !trimmed.endsWith(")")) {
            throw new IllegalStateException("腾讯文档响应格式不符合预期。");
        }
        return trimmed.substring(JSONP_PREFIX.length(), trimmed.length() - 1);
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("腾讯文档响应缺少 " + fieldName + "。");
        }
        return value;
    }

    public record SyncResult(Path output, String title, String lastSaveTimestamp) {
    }

    private record SourceDocument(URI sourceUrl, String documentId, String sheetId) {
        private static SourceDocument from(String sourceUrl) {
            try {
                URI uri = new URI(sourceUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !QQ_DOCS_HOST.equalsIgnoreCase(uri.getHost())) {
                    throw new IllegalArgumentException("腾讯文档地址必须使用 https://docs.qq.com/sheet/...。");
                }
                String[] parts = uri.getPath().split("/");
                if (parts.length != 3 || !"sheet".equals(parts[1]) || parts[2].isBlank()) {
                    throw new IllegalArgumentException("腾讯文档地址必须使用 /sheet/{documentId} 格式。");
                }
                String sheetId = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("tab");
                if (sheetId == null || sheetId.isBlank()) {
                    throw new IllegalArgumentException("腾讯文档地址缺少 tab 工作表参数。");
                }
                return new SourceDocument(uri, parts[2], sheetId);
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("腾讯文档地址无效。", exception);
            }
        }
    }
}
