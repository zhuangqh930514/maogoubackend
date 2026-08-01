package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiChallengerReleaseService;
import com.maogou.stock.service.research.AiImportedModelQualificationService;
import com.maogou.stock.service.research.AiModelQualityGate;
import com.maogou.stock.service.research.OnnxModelHealthValidator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiImportedModelQualificationServiceImplTest {

    @Test
    void keepsCandidateWhenQualityGateFails() {
        AppProperties properties = new AppProperties();
        AiModelVersionMapper modelMapper = mock(AiModelVersionMapper.class);
        AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
        AiModelQualityGate qualityGate = mock(AiModelQualityGate.class);
        OnnxModelHealthValidator onnxValidator = mock(OnnxModelHealthValidator.class);
        AiChallengerReleaseService challenger = mock(AiChallengerReleaseService.class);
        AiModelVersion model = model("CANDIDATE");
        when(modelMapper.selectById(7L)).thenReturn(model);
        when(datasetMapper.selectById(9L)).thenReturn(dataset());
        when(qualityGate.evaluate(any(), anyInt(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(new AiModelQualityGate.Evaluation(false, java.util.List.of(
                        new AiModelQualityGate.Check("TEST_ROC_AUC", false, ">=0.55", "0.51", "测试集不足"))));

        var service = new AiImportedModelQualificationServiceImpl(properties, modelMapper, datasetMapper,
                qualityGate, onnxValidator, challenger, new ObjectMapper());
        var result = service.qualifyAndCreateShadow(7L, LocalDateTime.of(2026, 8, 1, 16, 0));

        assertFalse(result.qualityGatePassed());
        assertEquals("CANDIDATE", result.modelStatus());
        verify(modelMapper, never()).updateById(any(AiModelVersion.class));
        verify(challenger, never()).createFromValidatedModel(any(), any());
    }

    @Test
    void validatesCandidateBeforeCreatingShadowChallenger() {
        AppProperties properties = new AppProperties();
        AiModelVersionMapper modelMapper = mock(AiModelVersionMapper.class);
        AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
        AiModelQualityGate qualityGate = mock(AiModelQualityGate.class);
        OnnxModelHealthValidator onnxValidator = mock(OnnxModelHealthValidator.class);
        AiChallengerReleaseService challenger = mock(AiChallengerReleaseService.class);
        AiModelVersion model = model("CANDIDATE");
        when(modelMapper.selectById(7L)).thenReturn(model);
        when(datasetMapper.selectById(9L)).thenReturn(dataset());
        when(qualityGate.evaluate(any(), anyInt(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(new AiModelQualityGate.Evaluation(true, java.util.List.of(
                        new AiModelQualityGate.Check("ALL", true, "pass", "pass", "通过"))));
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 88L;
        release.status = "SHADOW";
        when(challenger.createFromValidatedModel(eq(7L), any())).thenReturn(release);

        var service = new AiImportedModelQualificationServiceImpl(properties, modelMapper, datasetMapper,
                qualityGate, onnxValidator, challenger, new ObjectMapper());
        var result = service.qualifyAndCreateShadow(7L, LocalDateTime.of(2026, 8, 1, 16, 0));

        assertTrue(result.qualityGatePassed());
        assertEquals("VALIDATED", result.modelStatus());
        assertEquals(88L, result.challengerId());
        verify(modelMapper).updateById(model);
        verify(onnxValidator).verify(any());
    }

    private static AiModelVersion model(String status) {
        AiModelVersion model = new AiModelVersion();
        model.id = 7L;
        model.trainingDatasetId = 9L;
        model.status = status;
        model.sampleCount = 1000;
        model.metricsJson = "{}";
        model.calibrationJson = "{}";
        model.artifactUri = "file:/tmp/model.onnx";
        return model;
    }

    private static AiTrainingDataset dataset() {
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 9L;
        dataset.status = "READY";
        return dataset;
    }
}
