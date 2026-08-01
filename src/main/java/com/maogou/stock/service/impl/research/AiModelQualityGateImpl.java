package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.service.research.AiModelQualityGate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiModelQualityGateImpl implements AiModelQualityGate {

    @Override
    public Evaluation evaluate(
            AiTrainingDataset dataset,
            int sampleCount,
            JsonNode metrics,
            JsonNode calibration,
            int minimumSamples,
            double minimumTestRocAuc
    ) {
        List<Check> checks = new ArrayList<>();
        checks.add(check("DATASET_READY", dataset != null && "READY".equals(dataset.status),
                "READY", dataset == null ? "MISSING" : dataset.status, "训练数据集必须是 READY"));
        int requiredSamples = Math.max(1, minimumSamples);
        checks.add(check("SAMPLE_COUNT", sampleCount >= requiredSamples
                        && dataset != null && dataset.rowCount != null && dataset.rowCount == sampleCount,
                ">=" + requiredSamples + " 且与数据集一致",
                dataset == null ? String.valueOf(sampleCount) : sampleCount + "/" + dataset.rowCount,
                "样本数不足或与不可变数据集行数不一致"));
        checks.add(check("TIME_SPLIT", validTimeSplit(dataset),
                "train < validation < test 且 purge/embargo >= 5",
                dataset == null ? "MISSING" : splitValue(dataset),
                "训练、验证、测试时间切分或隔离窗口不完整"));
        checks.add(check("ONNX_EXPORTED", metrics != null && metrics.path("artifacts").path("onnxExported").asBoolean(false),
                "true", value(metrics, "artifacts.onnxExported"), "模型未导出 ONNX"));
        checks.add(check("ONNX_PARITY", metrics != null && metrics.path("artifacts").path("onnxParity")
                        .path("verified").asBoolean(false),
                "true", value(metrics, "artifacts.onnxParity.verified"), "ONNX 与训练推理未通过一致性验证"));

        double validationAuc = number(metrics, "splits.validation.rocAuc");
        double testAuc = number(metrics, "splits.test.rocAuc");
        checks.add(check("VALIDATION_ROC_AUC", Double.isFinite(validationAuc) && validationAuc >= 0.55d,
                ">=0.55", numberText(validationAuc), "验证集 ROC-AUC 未达到 0.55"));
        double configuredMinimum = Math.max(0.52d, minimumTestRocAuc);
        checks.add(check("TEST_ROC_AUC", Double.isFinite(testAuc) && testAuc >= configuredMinimum,
                ">=" + configuredMinimum, numberText(testAuc), "测试集 ROC-AUC 未达到配置门槛"));

        boolean calibrationFitted = calibration != null && calibration.path("fitted").asBoolean(false)
                && metrics != null && metrics.path("calibration").path("fitted").asBoolean(false);
        checks.add(check("CALIBRATION", calibrationFitted,
                "fitted=true", calibrationFitted ? "true" : "false", "模型校准未完成"));
        return new Evaluation(checks.stream().allMatch(Check::passed), checks);
    }

    private static boolean validTimeSplit(AiTrainingDataset dataset) {
        if (dataset == null || dataset.trainStartDate == null || dataset.trainEndDate == null
                || dataset.validationStartDate == null || dataset.validationEndDate == null
                || dataset.testStartDate == null || dataset.testEndDate == null) {
            return false;
        }
        return dataset.trainStartDate.isBefore(dataset.trainEndDate.plusDays(1))
                && dataset.trainEndDate.isBefore(dataset.validationStartDate)
                && dataset.validationStartDate.isBefore(dataset.validationEndDate.plusDays(1))
                && dataset.validationEndDate.isBefore(dataset.testStartDate)
                && dataset.testStartDate.isBefore(dataset.testEndDate.plusDays(1))
                && (dataset.purgeTradingDays == null || dataset.purgeTradingDays >= 5)
                && (dataset.embargoTradingDays == null || dataset.embargoTradingDays >= 5);
    }

    private static String splitValue(AiTrainingDataset dataset) {
        return dataset.trainStartDate + ".." + dataset.trainEndDate + " / "
                + dataset.validationStartDate + ".." + dataset.validationEndDate + " / "
                + dataset.testStartDate + ".." + dataset.testEndDate;
    }

    private static Check check(String key, boolean passed, String expected, String actual, String message) {
        return new Check(key, passed, expected, actual, passed ? "通过" : message);
    }

    private static double number(JsonNode node, String path) {
        if (node == null) {
            return Double.NaN;
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            current = current.path(part);
        }
        return current.isNumber() ? current.asDouble() : Double.NaN;
    }

    private static String value(JsonNode node, String path) {
        if (node == null) {
            return "MISSING";
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            current = current.path(part);
        }
        return current.isMissingNode() ? "MISSING" : current.toString();
    }

    private static String numberText(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "MISSING";
    }
}
