package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.service.research.HistoricalRawEvidenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemHistoricalRawEvidenceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void writesChecksumAddressedEvidenceAtomicallyAndIsIdempotent() throws Exception {
        FileSystemHistoricalRawEvidenceStore store = new FileSystemHistoricalRawEvidenceStore(
                new ObjectMapper().findAndRegisterModules(), tempDir);
        KlineSeriesSnapshot series = series("REAL_PROVIDER", "NONE");

        HistoricalRawEvidenceStore.RawArtifact first = store.stage(
                99L, "REAL_PROVIDER", "DAILY_BAR_NONE", "STOCK_000001_2026-08-01_NONE",
                series, LocalDateTime.of(2026, 8, 1, 16, 0));
        HistoricalRawEvidenceStore.RawArtifact second = store.stage(
                99L, "REAL_PROVIDER", "DAILY_BAR_NONE", "STOCK_000001_2026-08-01_NONE",
                series, LocalDateTime.of(2026, 8, 1, 16, 0));

        assertThat(first.objectChecksum()).isNotBlank();
        assertThat(first.objectSize()).isPositive();
        assertThat(first.objectUri()).isEqualTo(second.objectUri());
        assertThat(first.objectChecksum()).isEqualTo(second.objectChecksum());
        assertThat(first.manifestJson()).contains("MAOGOU_HISTORICAL_RAW_EVIDENCE_V1");
        assertThat(Files.isRegularFile(Path.of(java.net.URI.create(first.objectUri())))).isTrue();
        assertThat(Files.list(tempDir).toList()).isNotEmpty();
    }

    @Test
    void rejectsSyntheticSourceAndFutureBars() {
        FileSystemHistoricalRawEvidenceStore store = new FileSystemHistoricalRawEvidenceStore(
                new ObjectMapper().findAndRegisterModules(), tempDir);
        KlineSeriesSnapshot future = series("REAL_PROVIDER", "NONE");

        assertThatThrownBy(() -> store.stage(99L, "LOCAL_TEST_FIXTURE", "DAILY_BAR_NONE",
                "fixture", future, LocalDateTime.of(2026, 8, 1, 16, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝");

        List<KlinePointResponse> points = new ArrayList<>(future.points());
        points.add(new KlinePointResponse(LocalDate.of(2026, 8, 2), BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L, BigDecimal.TEN));
        KlineSeriesSnapshot invalid = KlineSeriesSnapshot.create(
                "000001", "day", "NONE", "REAL_PROVIDER", LocalDateTime.of(2026, 8, 1, 16, 0),
                LocalDateTime.of(2026, 8, 1, 16, 0), points);

        assertThatThrownBy(() -> store.stage(99L, "REAL_PROVIDER", "DAILY_BAR_NONE", "future", invalid,
                LocalDateTime.of(2026, 8, 1, 16, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来日期");
    }

    private static KlineSeriesSnapshot series(String source, String adjustment) {
        List<KlinePointResponse> points = new ArrayList<>();
        LocalDate end = LocalDate.of(2026, 8, 1);
        for (int index = 24; index >= 0; index--) {
            LocalDate date = end.minusDays(index);
            BigDecimal close = BigDecimal.valueOf(10 + index * 0.1d);
            points.add(new KlinePointResponse(date, close, close, close, close, 100L, BigDecimal.TEN));
        }
        points.sort(Comparator.comparing(KlinePointResponse::tradeDate));
        return KlineSeriesSnapshot.create("000001", "day", adjustment, source,
                end.atTime(16, 0), end.atTime(16, 0), points);
    }
}
