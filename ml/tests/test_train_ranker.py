import json
import importlib.util
from pathlib import Path

import numpy as np
import pytest
import joblib

from ml.train_ranker import _export_onnx, load_dataset, train_ranker


def _write_dataset(path: Path) -> None:
    rows = []
    split_sizes = {"TRAIN": 24, "VALIDATION": 12, "TEST": 12}
    sample_id = 1
    for split, size in split_sizes.items():
        for index in range(size):
            momentum = (index % 6) / 5
            value = ((index * 3) % 7) / 6
            positive = momentum + value > 0.9
            rows.append(
                {
                    "sampleId": sample_id,
                    "labelId": 1000 + sample_id,
                    "stockCode": f"600{sample_id:03d}",
                    "tradeDate": f"2026-{1 + (sample_id - 1) // 24:02d}-{1 + index:02d}",
                    "split": split,
                    "features": {"momentum": momentum, "value": value, "ignored": "text"},
                    "target": {
                        "excessReturn": 0.03 if positive else -0.02,
                        "labelScore": 80 if positive else 20,
                    },
                }
            )
            sample_id += 1
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")


def test_load_dataset_preserves_declared_date_splits_and_feature_order(tmp_path: Path) -> None:
    dataset_path = tmp_path / "dataset.jsonl"
    _write_dataset(dataset_path)

    dataset = load_dataset(dataset_path)

    assert dataset.feature_names == ["momentum", "value"]
    assert dataset.splits.tolist().count("TRAIN") == 24
    assert dataset.splits.tolist().count("VALIDATION") == 12
    assert dataset.splits.tolist().count("TEST") == 12
    assert np.isfinite(dataset.features).all()


def test_train_ranker_exports_calibrated_baseline_manifest_metrics_and_optional_outputs(
    tmp_path: Path,
) -> None:
    dataset_path = tmp_path / "dataset.jsonl"
    output_dir = tmp_path / "artifacts"
    _write_dataset(dataset_path)

    result = train_ranker(
        dataset_path=dataset_path,
        output_dir=output_dir,
        random_seed=930514,
        enable_lightgbm=True,
        export_onnx=True,
    )

    assert result.algorithm in {"LOGISTIC_REGRESSION", "LIGHTGBM_RANKER"}
    assert result.model_path.exists()
    assert result.feature_manifest_path.exists()
    assert result.metrics_path.exists()
    manifest = json.loads(result.feature_manifest_path.read_text(encoding="utf-8"))
    metrics = json.loads(result.metrics_path.read_text(encoding="utf-8"))
    assert manifest["features"] == ["momentum", "value"]
    assert manifest["randomSeed"] == 930514
    assert metrics["randomSeed"] == 930514
    assert metrics["parameters"]
    assert metrics["calibration"]["method"] == "sigmoid"
    assert metrics["calibration"]["fitSplit"] == "TRAIN"
    assert "coefficient" in metrics["calibration"]
    assert "intercept" in metrics["calibration"]
    assert metrics["actionThresholds"]["fitSplit"] == "TRAIN"
    assert set(metrics["splits"]) == {"train", "validation", "test"}
    assert all("brierScore" in metrics["splits"][name] for name in metrics["splits"])
    onnx_dependencies_available = importlib.util.find_spec("skl2onnx") is not None and (
        result.algorithm == "LOGISTIC_REGRESSION"
        or importlib.util.find_spec("onnxmltools") is not None
    )
    if onnx_dependencies_available:
        assert result.onnx_path is not None
        assert result.onnx_path.exists()
        assert manifest["onnxOutput"]["name"]
        assert manifest["onnxOutput"]["kind"] in {"PROBABILITY_UP", "RAW_SCORE"}
        assert manifest["calibration"] == metrics["calibration"]
        assert metrics["artifacts"]["onnxSha256"]


def test_validation_and_test_extremes_cannot_change_train_fitted_state(tmp_path: Path) -> None:
    original_path = tmp_path / "original.jsonl"
    changed_path = tmp_path / "changed.jsonl"
    _write_dataset(original_path)
    rows = [json.loads(line) for line in original_path.read_text(encoding="utf-8").splitlines()]
    for row in rows:
        if row["split"] != "TRAIN":
            row["features"]["momentum"] *= 1_000_000
            row["features"]["futureOnlyFeature"] = 999_999_999
    changed_path.write_text(
        "\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8"
    )

    original = train_ranker(
        original_path, tmp_path / "original", enable_lightgbm=False, export_onnx=False
    )
    changed = train_ranker(
        changed_path, tmp_path / "changed", enable_lightgbm=False, export_onnx=False
    )

    original_bundle = joblib.load(original.model_path)
    changed_bundle = joblib.load(changed.model_path)
    original_metrics = json.loads(original.metrics_path.read_text(encoding="utf-8"))
    changed_metrics = json.loads(changed.metrics_path.read_text(encoding="utf-8"))
    assert original_bundle["featureNames"] == changed_bundle["featureNames"]
    assert "futureOnlyFeature" not in changed_bundle["featureNames"]
    np.testing.assert_allclose(
        original_bundle["model"].named_steps["imputer"].statistics_,
        changed_bundle["model"].named_steps["imputer"].statistics_,
    )
    np.testing.assert_allclose(
        original_bundle["model"].named_steps["scaler"].mean_,
        changed_bundle["model"].named_steps["scaler"].mean_,
    )
    assert original_metrics["calibration"] == changed_metrics["calibration"]
    assert original_metrics["actionThresholds"] == changed_metrics["actionThresholds"]


def test_rejects_overlapping_sample_ids_across_splits(tmp_path: Path) -> None:
    dataset_path = tmp_path / "bad.jsonl"
    dataset_path.write_text(
        "\n".join(
            [
                json.dumps({"sampleId": 1, "split": "TRAIN", "features": {"x": 1}, "target": {"excessReturn": 1}}),
                json.dumps({"sampleId": 1, "split": "TEST", "features": {"x": 2}, "target": {"excessReturn": -1}}),
            ]
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="multiple splits"):
        load_dataset(dataset_path)


def test_rejects_duplicate_sample_ids_inside_the_same_split(tmp_path: Path) -> None:
    dataset_path = tmp_path / "duplicate.jsonl"
    row = {
        "sampleId": 1,
        "split": "TRAIN",
        "features": {"x": 1},
        "target": {"excessReturn": 1},
    }
    dataset_path.write_text(
        "\n".join([json.dumps(row), json.dumps(row)]) + "\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="duplicate sample"):
        load_dataset(dataset_path)


def test_exports_lightgbm_ranker_to_onnx_when_optional_dependencies_exist(
    tmp_path: Path,
) -> None:
    lightgbm = pytest.importorskip("lightgbm")
    pytest.importorskip("onnxmltools")
    onnx = pytest.importorskip("onnx")
    reference = pytest.importorskip("onnx.reference")
    features = np.asarray(
        [[0.0, 0.0], [1.0, 0.0], [0.0, 1.0], [1.0, 1.0]] * 2,
        dtype=np.float64,
    )
    labels = np.asarray([0, 1, 0, 1] * 2)
    ranker = lightgbm.LGBMRanker(
        objective="lambdarank", n_estimators=5, min_child_samples=1, verbosity=-1
    )
    ranker.fit(features, labels, group=[4, 4])

    output = _export_onnx(ranker, "LIGHTGBM_RANKER", 2, tmp_path / "ranker.onnx")

    assert output is not None
    assert output.exists()
    assert output.stat().st_size > 0
    evaluator = reference.ReferenceEvaluator(onnx.load(output))
    actual = np.asarray(
        evaluator.run(None, {"features": features.astype(np.float32)})[0]
    ).reshape(-1)
    expected = ranker.predict(features)
    np.testing.assert_allclose(actual, expected, rtol=1e-5, atol=1e-6)
    assert ranker.booster_.dump_model(num_iteration=1)["objective"].startswith(
        "lambdarank"
    )
