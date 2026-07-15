package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AiResearchUnifiedArchitectureContractTest {

    private static final Path PRODUCTION_JAVA = Path.of("src/main/java");

    @Test
    void productionUsesOnlyUnifiedResearchPackagesApisAndTables() throws IOException {
        List<Path> javaFiles = javaFiles();
        String productionText = join(javaFiles);

        List<String> forbidden = List.of(
                "domain.entity.v2",
                "mapper.v2",
                "service.v2",
                "service.impl.v2",
                "AiLearningController",
                "AiEvolutionController",
                "/api/ai/learning",
                "/api/ai/evolution",
                "ai_sample_v2",
                "ai_prediction_v2",
                "ai_label_v2",
                "ai_factor_value_v2",
                "ai_factor_performance_v2",
                "AiLearningJobLog",
                "AiLearningJobLogMapper",
                "ai_learning_job_log",
                "LEGACY"
        );
        assertThat(forbidden.stream().filter(productionText::contains).toList()).isEmpty();
        assertThat(javaFiles)
                .noneMatch(path -> path.getFileName().toString().matches(".*V2.*\\.java"));
    }

    @Test
    void formalSchemaContainsTheUnifiedResearchSourcesOfTruth() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));

        assertThat(schema).contains(
                "CREATE TABLE IF NOT EXISTS ai_research_universe",
                "CREATE TABLE IF NOT EXISTS ai_source_observation",
                "CREATE TABLE IF NOT EXISTS ai_sample_label",
                "CREATE TABLE IF NOT EXISTS ai_prediction_evaluation",
                "CREATE TABLE IF NOT EXISTS ai_daily_decision_snapshot",
                "CREATE TABLE IF NOT EXISTS ai_analysis_report_prediction"
        );
        List<String> forbidden = List.of(
                "ai_sample_v2",
                "ai_prediction_v2",
                "ai_label_v2",
                "ai_factor_value_v2",
                "ai_factor_performance_v2"
        );
        assertThat(forbidden.stream().filter(schema::contains).toList()).isEmpty();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(PRODUCTION_JAVA)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String join(List<Path> files) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            text.append('\n').append(file).append('\n').append(Files.readString(file));
        }
        return text.toString();
    }
}
