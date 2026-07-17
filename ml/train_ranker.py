#!/usr/bin/env python3
"""Train a reproducible ranker baseline from an immutable Task 17 JSONL dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

for thread_variable in (
    "OMP_NUM_THREADS",
    "OPENBLAS_NUM_THREADS",
    "MKL_NUM_THREADS",
    "NUMEXPR_NUM_THREADS",
):
    os.environ.setdefault(thread_variable, "1")

import joblib
import numpy as np
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, log_loss, roc_auc_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler


TRAINER_VERSION = "TRAIN_RANKER_V2_1"
VALID_SPLITS = ("TRAIN", "VALIDATION", "TEST")


@dataclass(frozen=True)
class TrainingDataset:
    features: np.ndarray
    labels: np.ndarray
    splits: np.ndarray
    sample_ids: np.ndarray
    trade_dates: np.ndarray
    feature_names: list[str]


@dataclass(frozen=True)
class TrainingResult:
    algorithm: str
    model_path: Path
    feature_manifest_path: Path
    metrics_path: Path
    onnx_path: Path | None


def _flatten_numeric(value: Any, prefix: str = "") -> dict[str, float]:
    flattened: dict[str, float] = {}
    if isinstance(value, dict):
        for key in sorted(value):
            child_prefix = f"{prefix}.{key}" if prefix else key
            flattened.update(_flatten_numeric(value[key], child_prefix))
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        flattened[prefix] = float(value)
    return flattened


def _target(row: dict[str, Any]) -> int:
    target = row.get("target") or {}
    excess_return = target.get("excessReturn")
    if excess_return is not None:
        return int(float(excess_return) > 0.0)
    label_score = target.get("labelScore")
    if label_score is not None:
        return int(float(label_score) >= 50.0)
    raise ValueError("each row requires target.excessReturn or target.labelScore")


def load_dataset(dataset_path: str | Path) -> TrainingDataset:
    path = Path(dataset_path)
    sample_splits: dict[int, str] = {}
    feature_names: set[str] = set()
    labels: list[int] = []
    splits: list[str] = []
    sample_ids: list[int] = []
    trade_dates: list[str] = []
    for _, row in _dataset_rows(path):
        split = str(row.get("split", "")).upper()
        if split not in VALID_SPLITS:
            raise ValueError(f"unknown split: {split}")
        sample_id = int(row["sampleId"])
        previous_split = sample_splits.get(sample_id)
        if previous_split is not None:
            if previous_split != split:
                raise ValueError(f"sample {sample_id} appears in multiple splits")
            raise ValueError(f"duplicate sample {sample_id} inside split {split}")
        sample_splits[sample_id] = split
        flattened = _flatten_numeric(row.get("features") or {})
        if not flattened:
            raise ValueError(f"sample {sample_id} has no numeric features")
        if split == "TRAIN":
            feature_names.update(flattened)
        labels.append(_target(row))
        splits.append(split)
        sample_ids.append(sample_id)
        trade_dates.append(str(row.get("tradeDate", "")))
    if not sample_ids:
        raise ValueError("dataset is empty")

    ordered_features = sorted(feature_names)
    feature_indexes = {name: index for index, name in enumerate(ordered_features)}
    matrix = np.full((len(sample_ids), len(ordered_features)), np.nan, dtype=np.float64)
    for row_index, (_, row) in enumerate(_dataset_rows(path)):
        flattened = _flatten_numeric(row.get("features") or {})
        for name, value in flattened.items():
            column_index = feature_indexes.get(name)
            if column_index is not None:
                matrix[row_index, column_index] = value
    split_values = np.asarray(splits, dtype=str)
    train_matrix = matrix[split_values == "TRAIN"]
    selected_columns = np.asarray(
        [
            np.isfinite(train_matrix[:, index]).any()
            and float(np.nanvar(train_matrix[:, index])) > 0.0
            for index in range(train_matrix.shape[1])
        ],
        dtype=bool,
    )
    if not np.any(selected_columns):
        raise ValueError("TRAIN split requires at least one non-constant numeric feature")
    selected_features = [
        name for name, selected in zip(ordered_features, selected_columns) if selected
    ]
    return TrainingDataset(
        features=matrix[:, selected_columns],
        labels=np.asarray(labels, dtype=np.int64),
        splits=split_values,
        sample_ids=np.asarray(sample_ids, dtype=np.int64),
        trade_dates=np.asarray(trade_dates, dtype=str),
        feature_names=selected_features,
    )


def _dataset_rows(path: Path) -> Iterable[tuple[int, dict[str, Any]]]:
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                yield line_number, json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc


def _require_usable_splits(dataset: TrainingDataset) -> None:
    for split in VALID_SPLITS:
        mask = dataset.splits == split
        if not np.any(mask):
            raise ValueError(f"dataset requires a non-empty {split} split")
    train_labels = dataset.labels[dataset.splits == "TRAIN"]
    if np.unique(train_labels).size < 2:
        raise ValueError("TRAIN split requires both target classes")


def _baseline(seed: int) -> Pipeline:
    return Pipeline(
        steps=[
            ("imputer", SimpleImputer(strategy="median")),
            ("scaler", StandardScaler()),
            (
                "classifier",
                LogisticRegression(
                    C=1.0,
                    max_iter=1000,
                    class_weight="balanced",
                    random_state=seed,
                ),
            ),
        ]
    )


def _fit_calibrator(scores: np.ndarray, labels: np.ndarray, seed: int) -> LogisticRegression | None:
    if np.unique(labels).size < 2:
        return None
    calibrator = LogisticRegression(random_state=seed, max_iter=1000)
    calibrator.fit(scores.reshape(-1, 1), labels)
    return calibrator


def _calibrated_probability(
    scores: np.ndarray,
    calibrator: LogisticRegression | None,
) -> np.ndarray:
    if calibrator is not None:
        return calibrator.predict_proba(scores.reshape(-1, 1))[:, 1]
    clipped = np.clip(scores, -35.0, 35.0)
    return 1.0 / (1.0 + np.exp(-clipped))


def _calibration_contract(calibrator: LogisticRegression | None) -> dict[str, Any]:
    if calibrator is None:
        return {
            "method": "sigmoid",
            "fitSplit": "TRAIN",
            "fitted": False,
            "coefficient": 1.0,
            "intercept": 0.0,
        }
    return {
        "method": "sigmoid",
        "fitSplit": "TRAIN",
        "fitted": True,
        "coefficient": float(calibrator.coef_[0][0]),
        "intercept": float(calibrator.intercept_[0]),
    }


def _action_threshold_contract(train_probability: np.ndarray) -> dict[str, Any]:
    if train_probability.size == 0:
        raise ValueError("TRAIN split requires probabilities for action thresholds")
    return {
        "fitSplit": "TRAIN",
        "method": "TRAIN_PROBABILITY_QUANTILES",
        "avoidUpperBound": float(np.quantile(train_probability, 0.30)),
        "recommendLowerBound": float(np.quantile(train_probability, 0.70)),
    }


def _split_metrics(labels: np.ndarray, probability: np.ndarray) -> dict[str, Any]:
    clipped = np.clip(probability, 1e-7, 1.0 - 1e-7)
    metrics: dict[str, Any] = {
        "sampleCount": int(labels.size),
        "brierScore": float(brier_score_loss(labels, clipped)),
        "logLoss": float(log_loss(labels, clipped, labels=[0, 1])),
        "accuracy": float(accuracy_score(labels, clipped >= 0.5)),
    }
    metrics["rocAuc"] = (
        float(roc_auc_score(labels, clipped)) if np.unique(labels).size == 2 else None
    )
    return metrics


def _groups(dates: np.ndarray) -> list[int]:
    if dates.size == 0:
        return []
    groups: list[int] = []
    current = dates[0]
    count = 0
    for date in dates:
        if date != current:
            groups.append(count)
            current = date
            count = 0
        count += 1
    groups.append(count)
    return groups


def _try_lightgbm(
    dataset: TrainingDataset,
    seed: int,
) -> tuple[Any, np.ndarray, dict[str, Any]] | None:
    try:
        from lightgbm import LGBMRanker
    except ImportError:
        return None

    train_mask = dataset.splits == "TRAIN"
    validation_mask = dataset.splits == "VALIDATION"
    train_order = np.argsort(dataset.trade_dates[train_mask], kind="stable")
    validation_order = np.argsort(dataset.trade_dates[validation_mask], kind="stable")
    x_train = dataset.features[train_mask][train_order]
    y_train = dataset.labels[train_mask][train_order]
    train_dates = dataset.trade_dates[train_mask][train_order]
    x_validation = dataset.features[validation_mask][validation_order]
    y_validation = dataset.labels[validation_mask][validation_order]
    validation_dates = dataset.trade_dates[validation_mask][validation_order]
    parameters = {
        "objective": "lambdarank",
        "n_estimators": 120,
        "learning_rate": 0.05,
        "num_leaves": 15,
        "min_child_samples": 5,
        "random_state": seed,
        "deterministic": True,
        "n_jobs": 1,
        "verbosity": -1,
    }
    ranker = LGBMRanker(**parameters)
    ranker.fit(
        x_train,
        y_train,
        group=_groups(train_dates),
        eval_set=[(x_validation, y_validation)],
        eval_group=[_groups(validation_dates)],
        eval_at=[1, 3, 5],
    )
    return ranker, ranker.booster_.predict(dataset.features), parameters


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _export_onnx(
    model: Any,
    algorithm: str,
    feature_count: int,
    output_path: Path,
) -> Path | None:
    if algorithm == "LIGHTGBM_RANKER":
        try:
            from lightgbm import Booster
            from onnxmltools import convert_lightgbm
            from onnxmltools.convert.common.data_types import FloatTensorType
        except ImportError:
            return None
        booster = model.booster_
        objective = str(booster.dump_model(num_iteration=1).get("objective", ""))
        if objective.startswith("lambdarank"):
            model_text = booster.model_to_string()
            objective_header = f"objective={objective}"
            objective_metadata = f"[objective: {objective}]"
            if objective_header not in model_text or objective_metadata not in model_text:
                raise ValueError("LightGBM ranker model is missing objective metadata")
            model_text = model_text.replace(
                objective_header, "objective=regression", 1
            ).replace(objective_metadata, "[objective: regression]", 1)
            booster = Booster(model_str=model_text)
        converted = convert_lightgbm(
            booster,
            initial_types=[("features", FloatTensorType([None, feature_count]))],
            target_opset=15,
        )
    else:
        try:
            from skl2onnx import convert_sklearn
            from skl2onnx.common.data_types import FloatTensorType
        except ImportError:
            return None
        estimator = model.steps[-1][1] if isinstance(model, Pipeline) else model
        converted = convert_sklearn(
            model,
            initial_types=[("features", FloatTensorType([None, feature_count]))],
            target_opset=15,
            options={id(estimator): {"zipmap": False}},
        )
    output_path.write_bytes(converted.SerializeToString())
    return output_path


def _onnx_output_contract(path: Path | None, algorithm: str) -> dict[str, Any] | None:
    if path is None:
        return None
    try:
        import onnx
    except ImportError:
        return None
    output_names = [output.name for output in onnx.load(path).graph.output]
    if not output_names:
        raise ValueError("ONNX model has no declared output")
    if algorithm == "LOGISTIC_REGRESSION":
        selected = next(
            (name for name in output_names if "probab" in name.lower()), output_names[-1]
        )
        return {"name": selected, "index": 1, "kind": "PROBABILITY_UP"}
    selected = next(
        (name for name in output_names if "label" not in name.lower()), output_names[-1]
    )
    return {"name": selected, "index": 0, "kind": "RAW_SCORE"}


def train_ranker(
    dataset_path: str | Path,
    output_dir: str | Path,
    random_seed: int = 930514,
    enable_lightgbm: bool = True,
    export_onnx: bool = True,
) -> TrainingResult:
    dataset = load_dataset(dataset_path)
    _require_usable_splits(dataset)
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)

    train_mask = dataset.splits == "TRAIN"
    validation_mask = dataset.splits == "VALIDATION"
    baseline = _baseline(random_seed)
    baseline.fit(dataset.features[train_mask], dataset.labels[train_mask])
    baseline_scores = baseline.decision_function(dataset.features)
    baseline_calibrator = _fit_calibrator(
        baseline_scores[train_mask], dataset.labels[train_mask], random_seed
    )
    baseline_probability = _calibrated_probability(baseline_scores, baseline_calibrator)
    baseline_auc = _split_metrics(
        dataset.labels[validation_mask], baseline_probability[validation_mask]
    )["rocAuc"]

    algorithm = "LOGISTIC_REGRESSION"
    selected_model: Any = baseline
    selected_scores = baseline_scores
    selected_calibrator = baseline_calibrator
    parameters: dict[str, Any] = {
        "C": 1.0,
        "class_weight": "balanced",
        "max_iter": 1000,
        "random_state": random_seed,
        "scaler": "standard",
        "imputer": "median",
    }
    candidates: dict[str, Any] = {
        "logisticRegression": {"validationRocAuc": baseline_auc}
    }

    lightgbm = _try_lightgbm(dataset, random_seed) if enable_lightgbm else None
    if lightgbm is not None:
        ranker, ranker_scores, ranker_parameters = lightgbm
        ranker_calibrator = _fit_calibrator(
            ranker_scores[train_mask], dataset.labels[train_mask], random_seed
        )
        ranker_probability = _calibrated_probability(ranker_scores, ranker_calibrator)
        ranker_auc = _split_metrics(
            dataset.labels[validation_mask], ranker_probability[validation_mask]
        )["rocAuc"]
        candidates["lightgbmRanker"] = {"validationRocAuc": ranker_auc}
        baseline_score = -1.0 if baseline_auc is None else baseline_auc
        ranker_score = -1.0 if ranker_auc is None else ranker_auc
        if ranker_score > baseline_score:
            algorithm = "LIGHTGBM_RANKER"
            selected_model = ranker
            selected_scores = ranker_scores
            selected_calibrator = ranker_calibrator
            parameters = ranker_parameters

    probability = _calibrated_probability(selected_scores, selected_calibrator)
    action_thresholds = _action_threshold_contract(probability[train_mask])
    split_metrics = {
        split.lower(): _split_metrics(
            dataset.labels[dataset.splits == split], probability[dataset.splits == split]
        )
        for split in VALID_SPLITS
    }
    model_path = output / "model.joblib"
    joblib.dump(
        {
            "trainerVersion": TRAINER_VERSION,
            "algorithm": algorithm,
            "randomSeed": random_seed,
            "featureNames": dataset.feature_names,
            "model": selected_model,
            "calibrator": selected_calibrator,
            "actionThresholds": action_thresholds,
        },
        model_path,
    )

    onnx_path = None
    if export_onnx:
        onnx_path = _export_onnx(
            selected_model, algorithm, len(dataset.feature_names), output / "model.onnx"
        )
    onnx_output = _onnx_output_contract(onnx_path, algorithm)
    calibration = _calibration_contract(selected_calibrator)
    manifest_path = output / "feature_manifest.json"
    manifest = {
        "schemaVersion": "FEATURE_MANIFEST_V2_1",
        "trainerVersion": TRAINER_VERSION,
        "randomSeed": random_seed,
        "features": dataset.feature_names,
        "dtype": "float32",
        "missingValuePolicy": "median_imputation",
        "datasetSha256": _sha256(Path(dataset_path)),
        "onnxOutput": onnx_output,
        "calibration": calibration,
        "actionThresholds": action_thresholds,
    }
    _write_json(manifest_path, manifest)
    metrics_path = output / "metrics.json"
    metrics = {
        "trainerVersion": TRAINER_VERSION,
        "algorithm": algorithm,
        "randomSeed": random_seed,
        "parameters": parameters,
        "calibration": calibration,
        "actionThresholds": action_thresholds,
        "splits": split_metrics,
        "candidates": candidates,
        "artifacts": {
            "modelSha256": _sha256(onnx_path or model_path),
            "joblibSha256": _sha256(model_path),
            "onnxSha256": _sha256(onnx_path) if onnx_path is not None else None,
            "featureManifestSha256": _sha256(manifest_path),
            "onnxExported": onnx_path is not None,
        },
    }
    _write_json(metrics_path, metrics)
    return TrainingResult(
        algorithm=algorithm,
        model_path=model_path,
        feature_manifest_path=manifest_path,
        metrics_path=metrics_path,
        onnx_path=onnx_path,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--random-seed", type=int, default=930514)
    parser.add_argument("--disable-lightgbm", action="store_true")
    parser.add_argument("--disable-onnx", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    result = train_ranker(
        dataset_path=args.dataset,
        output_dir=args.output_dir,
        random_seed=args.random_seed,
        enable_lightgbm=not args.disable_lightgbm,
        export_onnx=not args.disable_onnx,
    )
    print(
        json.dumps(
            {
                "algorithm": result.algorithm,
                "model": str(result.model_path),
                "featureManifest": str(result.feature_manifest_path),
                "metrics": str(result.metrics_path),
                "onnx": str(result.onnx_path) if result.onnx_path else None,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
