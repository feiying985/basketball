"""Evaluate a shot detector against hand-labeled clips.

The evaluation set is local (videos and labels are not committed). This tool
runs a detector over every labeled clip, matches detected shot events to the
labels by timestamp and reports event precision/recall, made-shot metrics,
make/miss classification accuracy and timestamp error.

Usage (from ``server``)::

    python -m tools.evaluate \
        --dataset /data/basketball-eval \
        --labels /data/basketball-eval/labels.csv \
        --detector classical \
        --out reports/2026-11-11-classical

The label CSV format is documented in ``tools/README.md``. A template is
available at ``tools/labels_template.csv``.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping, Protocol, Sequence

from app.schemas import AnalysisResult, ShotEvent

HoopRoi = tuple[float, float, float, float]
DEFAULT_TOLERANCE_SECONDS = 1.0
REQUIRED_COLUMNS = ("clip", "shot_id", "rim_time", "made")
SUPPORTED_DETECTORS = ("classical", "stub")


class LabelError(ValueError):
    """Raised when the label CSV cannot be parsed."""


@dataclass(frozen=True)
class Label:
    """One hand-labeled shot event."""

    clip: str
    shot_id: int
    rim_time: float
    made: bool
    release_time: float | None = None
    occluded: bool = False
    camera: str = ""
    lighting: str = ""
    hoop_roi: HoopRoi | None = None
    notes: str = ""

    @property
    def scenario(self) -> tuple[str, str]:
        return (self.camera or "unknown", self.lighting or "unknown")


@dataclass(frozen=True)
class MatchResult:
    """One-to-one event matching result for one clip."""

    pairs: tuple[tuple[ShotEvent, Label], ...]
    false_positives: tuple[ShotEvent, ...]
    false_negatives: tuple[Label, ...]


@dataclass(frozen=True)
class Metrics:
    tp: int
    fp: int
    fn: int
    precision: float
    recall: float
    f1: float
    made_tp: int
    made_fp: int
    made_fn: int
    made_precision: float
    made_recall: float
    made_correct: int
    made_evaluated: int
    made_accuracy: float | None
    time_mae: float | None
    time_p95: float | None
    fp_per_minute: float | None


@dataclass(frozen=True)
class ClipEvaluation:
    clip: str
    duration_seconds: float | None
    labels: tuple[Label, ...]
    detections: tuple[ShotEvent, ...]
    match: MatchResult | None
    metrics: Metrics | None
    error: str | None = None


@dataclass(frozen=True)
class ScenarioEvaluation:
    camera: str
    lighting: str
    clips: int
    metrics: Metrics


@dataclass(frozen=True)
class EvaluationReport:
    detector: str
    tolerance_seconds: float
    dataset: str
    generated_at: str
    clips: tuple[ClipEvaluation, ...]
    totals: Metrics | None
    scenarios: tuple[ScenarioEvaluation, ...]
    skipped: Mapping[str, str]


class Detector(Protocol):
    """The subset of ``ShotDetector`` used by this tool."""

    name: str

    def analyze(
        self,
        video_path: Path,
        *,
        hoop_roi: HoopRoi | None = None,
    ) -> AnalysisResult: ...


# ---------------------------------------------------------------------------
# Label loading
# ---------------------------------------------------------------------------


def load_labels(path: Path) -> list[Label]:
    """Load and validate a label CSV.

    Required columns are ``clip``, ``shot_id``, ``rim_time`` and ``made``;
    everything else is optional. The parser is strict about malformed rows so a
    typo cannot silently skew an evaluation.
    """

    labels: list[Label] = []
    seen: set[tuple[str, int]] = set()

    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise LabelError(f"{path}: 缺少表头")

        columns = {name.strip().lower(): name for name in reader.fieldnames}
        missing = [column for column in REQUIRED_COLUMNS if column not in columns]
        if missing:
            raise LabelError(
                f"{path}: 缺少必填列 {', '.join(missing)}；"
                f"需要 {', '.join(REQUIRED_COLUMNS)}"
            )

        for row in reader:
            line = reader.line_num
            values = {
                column: (row.get(original) or "").strip()
                for column, original in columns.items()
            }
            if not any(values.values()):
                continue

            label = _parse_label_row(values, line=line)
            key = (label.clip, label.shot_id)
            if key in seen:
                raise LabelError(
                    f"{path} 第 {line} 行: 重复的 shot_id "
                    f"{label.shot_id}（{label.clip}）"
                )
            seen.add(key)
            labels.append(label)

    if not labels:
        raise LabelError(f"{path}: 没有可评估的投篮事件")
    return labels


def _parse_label_row(values: Mapping[str, str], *, line: int) -> Label:
    clip = values.get("clip", "")
    if not clip:
        raise LabelError(f"第 {line} 行: clip 不能为空")

    shot_id = _parse_int(values.get("shot_id", ""), "shot_id", line=line)
    if shot_id <= 0:
        raise LabelError(f"第 {line} 行: shot_id 必须大于 0")

    rim_time = _parse_float(
        values.get("rim_time", ""), "rim_time", line=line, minimum=0.0
    )
    made = _parse_bool(values.get("made", ""), "made", line=line)
    release_time = _parse_optional_float(
        values.get("release_time", ""), "release_time", line=line, minimum=0.0
    )
    occluded = _parse_bool(
        values.get("occluded", ""), "occluded", line=line, default=False
    )
    hoop_roi = _parse_roi(values.get("hoop_roi", ""), line=line)

    return Label(
        clip=clip,
        shot_id=shot_id,
        rim_time=rim_time,
        made=made,
        release_time=release_time,
        occluded=occluded,
        camera=values.get("camera", ""),
        lighting=values.get("lighting", ""),
        hoop_roi=hoop_roi,
        notes=values.get("notes", ""),
    )


def _parse_int(raw: str, field: str, *, line: int) -> int:
    if not raw:
        raise LabelError(f"第 {line} 行: {field} 不能为空")
    try:
        return int(raw)
    except ValueError as exc:
        raise LabelError(f"第 {line} 行: {field} 需要整数，得到 {raw!r}") from exc


def _parse_float(
    raw: str,
    field: str,
    *,
    line: int,
    minimum: float | None = None,
) -> float:
    if not raw:
        raise LabelError(f"第 {line} 行: {field} 不能为空")
    try:
        value = float(raw)
    except ValueError as exc:
        raise LabelError(f"第 {line} 行: {field} 需要数值，得到 {raw!r}") from exc
    if not math.isfinite(value):
        raise LabelError(f"第 {line} 行: {field} 需要有限数值")
    if minimum is not None and value < minimum:
        raise LabelError(f"第 {line} 行: {field} 不能小于 {minimum}")
    return value


def _parse_optional_float(
    raw: str,
    field: str,
    *,
    line: int,
    minimum: float | None = None,
) -> float | None:
    if not raw:
        return None
    return _parse_float(raw, field, line=line, minimum=minimum)


def _parse_bool(
    raw: str,
    field: str,
    *,
    line: int,
    default: bool | None = None,
) -> bool:
    if not raw:
        if default is not None:
            return default
        raise LabelError(f"第 {line} 行: {field} 不能为空")
    lowered = raw.lower()
    if lowered in {"1", "true", "yes", "y", "是"}:
        return True
    if lowered in {"0", "false", "no", "n", "否"}:
        return False
    raise LabelError(f"第 {line} 行: {field} 需要 0/1 或 true/false，得到 {raw!r}")


def _parse_roi(raw: str, *, line: int | None = None) -> HoopRoi | None:
    if not raw:
        return None
    prefix = f"第 {line} 行: " if line is not None else ""
    parts = [part.strip() for part in raw.split(",")]
    if len(parts) != 4:
        raise LabelError(
            f"{prefix}hoop_roi 需要 4 个 0-1 数值 x,y,width,height"
        )
    try:
        x, y, width, height = (float(part) for part in parts)
    except ValueError as exc:
        raise LabelError(
            f"{prefix}hoop_roi 需要 4 个 0-1 数值 x,y,width,height"
        ) from exc
    if not (0 <= x < 1 and 0 <= y < 1 and 0 < width <= 1 and 0 < height <= 1):
        raise LabelError(f"{prefix}hoop_roi 的取值范围是 0-1，宽高必须大于 0")
    if x + width > 1 or y + height > 1:
        raise LabelError(f"{prefix}hoop_roi 不能超出画面范围")
    return (x, y, width, height)


# ---------------------------------------------------------------------------
# Matching and metrics
# ---------------------------------------------------------------------------


def match_events(
    detections: Sequence[ShotEvent],
    labels: Sequence[Label],
    *,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
) -> MatchResult:
    """Match detections to labels one-to-one by closest timestamp.

    All candidate pairs within the tolerance are sorted by absolute time
    difference and assigned greedily. This is enough for the sparse shot events
    in this project and avoids a Hungarian-assignment dependency; a globally
    optimal assignment can replace it later without changing the report shape.
    """

    if tolerance_seconds < 0:
        raise ValueError("tolerance_seconds 不能为负数")

    candidates: list[tuple[float, int, int]] = []
    for detection_index, detection in enumerate(detections):
        for label_index, label in enumerate(labels):
            difference = abs(detection.timestamp_seconds - label.rim_time)
            if difference <= tolerance_seconds:
                candidates.append((difference, detection_index, label_index))
    candidates.sort(key=lambda item: (item[0], item[1], item[2]))

    used_detections: set[int] = set()
    used_labels: set[int] = set()
    pairs: list[tuple[ShotEvent, Label]] = []
    for _difference, detection_index, label_index in candidates:
        if detection_index in used_detections or label_index in used_labels:
            continue
        used_detections.add(detection_index)
        used_labels.add(label_index)
        pairs.append((detections[detection_index], labels[label_index]))

    pairs.sort(key=lambda pair: pair[0].timestamp_seconds)
    false_positives = tuple(
        detection
        for index, detection in enumerate(detections)
        if index not in used_detections
    )
    false_negatives = tuple(
        label for index, label in enumerate(labels) if index not in used_labels
    )
    return MatchResult(
        pairs=tuple(pairs),
        false_positives=false_positives,
        false_negatives=false_negatives,
    )


def compute_metrics(
    match: MatchResult,
    detections: Sequence[ShotEvent],
    labels: Sequence[Label],
    *,
    duration_seconds: float | None = None,
) -> Metrics:
    tp = len(match.pairs)
    fp = len(match.false_positives)
    fn = len(match.false_negatives)

    made_detected = sum(1 for detection in detections if detection.made)
    made_labels = sum(1 for label in labels if label.made)
    made_tp = sum(1 for detection, label in match.pairs if detection.made and label.made)
    made_correct = sum(
        1 for detection, label in match.pairs if detection.made == label.made
    )

    made_fp = made_detected - made_tp
    made_fn = made_labels - made_tp
    time_errors = [
        abs(detection.timestamp_seconds - label.rim_time)
        for detection, label in match.pairs
    ]

    return Metrics(
        tp=tp,
        fp=fp,
        fn=fn,
        precision=_ratio(tp, tp + fp),
        recall=_ratio(tp, tp + fn),
        f1=_f1(tp, fp, fn),
        made_tp=made_tp,
        made_fp=made_fp,
        made_fn=made_fn,
        made_precision=_ratio(made_tp, made_tp + made_fp),
        made_recall=_ratio(made_tp, made_tp + made_fn),
        made_correct=made_correct,
        made_evaluated=tp,
        made_accuracy=_ratio(made_correct, tp) if tp else None,
        time_mae=(sum(time_errors) / len(time_errors)) if time_errors else None,
        time_p95=_percentile(time_errors, 95) if time_errors else None,
        fp_per_minute=(
            fp / (duration_seconds / 60.0) if duration_seconds and duration_seconds > 0 else None
        ),
    )


def _ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _f1(tp: int, fp: int, fn: int) -> float:
    precision = _ratio(tp, tp + fp)
    recall = _ratio(tp, tp + fn)
    return 2 * precision * recall / (precision + recall) if precision + recall else 0.0


def _percentile(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(percentile / 100.0 * len(ordered)) - 1)
    return ordered[index]


# ---------------------------------------------------------------------------
# Dataset evaluation
# ---------------------------------------------------------------------------


def evaluate_dataset(
    dataset: Path,
    labels: Sequence[Label],
    detector: Detector,
    *,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
    hoop_roi: HoopRoi | None = None,
    probe_duration: bool = True,
) -> EvaluationReport:
    """Run ``detector`` over every clip referenced by ``labels``."""

    if tolerance_seconds < 0:
        raise ValueError("tolerance_seconds 不能为负数")

    grouped: dict[str, list[Label]] = defaultdict(list)
    for label in labels:
        grouped[label.clip].append(label)

    clips: list[ClipEvaluation] = []
    for clip, clip_labels in grouped.items():
        path = _resolve_clip_path(dataset, clip)
        if not path.exists():
            clips.append(
                ClipEvaluation(
                    clip=clip,
                    duration_seconds=None,
                    labels=tuple(clip_labels),
                    detections=(),
                    match=None,
                    metrics=None,
                    error=f"视频不存在: {path}",
                )
            )
            continue

        try:
            roi = hoop_roi if hoop_roi is not None else _clip_hoop_roi(clip_labels)
            result = detector.analyze(path, hoop_roi=roi)
        except Exception as exc:  # noqa: BLE001 - one bad clip must not stop the run
            clips.append(
                ClipEvaluation(
                    clip=clip,
                    duration_seconds=None,
                    labels=tuple(clip_labels),
                    detections=(),
                    match=None,
                    metrics=None,
                    error=f"{type(exc).__name__}: {exc}",
                )
            )
            continue

        detections = tuple(result.shots)
        duration = _probe_duration(path) if probe_duration else None
        match = match_events(
            detections, clip_labels, tolerance_seconds=tolerance_seconds
        )
        metrics = compute_metrics(
            match, detections, clip_labels, duration_seconds=duration
        )
        clips.append(
            ClipEvaluation(
                clip=clip,
                duration_seconds=duration,
                labels=tuple(clip_labels),
                detections=detections,
                match=match,
                metrics=metrics,
            )
        )

    successful = tuple(clip for clip in clips if clip.metrics is not None)
    skipped = {clip.clip: clip.error for clip in clips if clip.error}

    return EvaluationReport(
        detector=getattr(detector, "name", "unknown"),
        tolerance_seconds=tolerance_seconds,
        dataset=str(dataset),
        generated_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        clips=tuple(clips),
        totals=_totals(successful),
        scenarios=_scenario_metrics(successful),
        skipped=skipped,
    )


def _resolve_clip_path(dataset: Path, clip: str) -> Path:
    path = Path(clip)
    return path if path.is_absolute() else dataset / path


def _clip_hoop_roi(labels: Sequence[Label]) -> HoopRoi | None:
    rois = {label.hoop_roi for label in labels if label.hoop_roi is not None}
    if len(rois) > 1:
        raise LabelError("同一 clip 的 hoop_roi 必须一致")
    return next(iter(rois)) if rois else None


def _totals(clips: Sequence[ClipEvaluation]) -> Metrics | None:
    if not clips:
        return None
    pairs: list[tuple[ShotEvent, Label]] = []
    false_positives: list[ShotEvent] = []
    false_negatives: list[Label] = []
    detections: list[ShotEvent] = []
    labels: list[Label] = []
    duration = 0.0

    for clip in clips:
        assert clip.match is not None
        pairs.extend(clip.match.pairs)
        false_positives.extend(clip.match.false_positives)
        false_negatives.extend(clip.match.false_negatives)
        detections.extend(clip.detections)
        labels.extend(clip.labels)
        if clip.duration_seconds:
            duration += clip.duration_seconds

    match = MatchResult(
        pairs=tuple(pairs),
        false_positives=tuple(false_positives),
        false_negatives=tuple(false_negatives),
    )
    return compute_metrics(match, detections, labels, duration_seconds=duration or None)


@dataclass
class _ScenarioBucket:
    """Accumulator for one (camera, lighting) scenario."""

    pairs: list[tuple[ShotEvent, Label]] = field(default_factory=list)
    false_positives: list[ShotEvent] = field(default_factory=list)
    false_negatives: list[Label] = field(default_factory=list)
    detections: list[ShotEvent] = field(default_factory=list)
    labels: list[Label] = field(default_factory=list)
    duration: float = 0.0
    clips: int = 0


def _scenario_metrics(
    clips: Sequence[ClipEvaluation],
) -> tuple[ScenarioEvaluation, ...]:
    buckets: dict[tuple[str, str], _ScenarioBucket] = {}

    for clip in clips:
        assert clip.match is not None
        scenarios = {label.scenario for label in clip.labels}
        false_positive_scenario = (
            next(iter(scenarios)) if len(scenarios) == 1 else ("unknown", "unknown")
        )
        for detection, label in clip.match.pairs:
            bucket = buckets.setdefault(label.scenario, _ScenarioBucket())
            bucket.pairs.append((detection, label))
            bucket.detections.append(detection)
            bucket.labels.append(label)
        for label in clip.match.false_negatives:
            bucket = buckets.setdefault(label.scenario, _ScenarioBucket())
            bucket.false_negatives.append(label)
            bucket.labels.append(label)
        for detection in clip.match.false_positives:
            bucket = buckets.setdefault(false_positive_scenario, _ScenarioBucket())
            bucket.false_positives.append(detection)
            bucket.detections.append(detection)

        for scenario in scenarios | {false_positive_scenario}:
            bucket = buckets.setdefault(scenario, _ScenarioBucket())
            bucket.clips += 1
            if clip.duration_seconds:
                bucket.duration += clip.duration_seconds

    result: list[ScenarioEvaluation] = []
    for (camera, lighting), bucket in sorted(buckets.items()):
        match = MatchResult(
            pairs=tuple(bucket.pairs),
            false_positives=tuple(bucket.false_positives),
            false_negatives=tuple(bucket.false_negatives),
        )
        metrics = compute_metrics(
            match,
            bucket.detections,
            bucket.labels,
            duration_seconds=bucket.duration or None,
        )
        result.append(
            ScenarioEvaluation(
                camera=camera,
                lighting=lighting,
                clips=bucket.clips,
                metrics=metrics,
            )
        )
    return tuple(result)


def _probe_duration(path: Path) -> float | None:
    """Read duration without decoding frames; ``None`` when metadata is unavailable."""

    try:
        import cv2
    except ImportError:
        return None

    capture = cv2.VideoCapture(str(path))
    try:
        if not capture.isOpened():
            return None
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
        frame_count = float(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0.0)
    finally:
        capture.release()
    if fps <= 0 or frame_count <= 0:
        return None
    return frame_count / fps


# ---------------------------------------------------------------------------
# Detector construction and reporting
# ---------------------------------------------------------------------------


def build_detector(backend: str) -> Detector:
    from app.services.shot_detection import create_detector

    return create_detector(backend)


def report_to_dict(report: EvaluationReport) -> dict[str, object]:
    return {
        "detector": report.detector,
        "tolerance_seconds": report.tolerance_seconds,
        "dataset": report.dataset,
        "generated_at": report.generated_at,
        "totals": asdict(report.totals) if report.totals else None,
        "scenarios": [asdict(scenario) for scenario in report.scenarios],
        "clips": [
            {
                "clip": clip.clip,
                "duration_seconds": clip.duration_seconds,
                "label_count": len(clip.labels),
                "detection_count": len(clip.detections),
                "metrics": asdict(clip.metrics) if clip.metrics else None,
                "error": clip.error,
                "false_positives": [
                    {
                        "timestamp_seconds": detection.timestamp_seconds,
                        "made": detection.made,
                    }
                    for detection in (
                        clip.match.false_positives if clip.match is not None else ()
                    )
                ],
                "false_negatives": [
                    {
                        "shot_id": label.shot_id,
                        "rim_time": label.rim_time,
                        "made": label.made,
                        "notes": label.notes,
                    }
                    for label in (
                        clip.match.false_negatives if clip.match is not None else ()
                    )
                ],
            }
            for clip in report.clips
        ],
        "skipped": dict(report.skipped),
    }


def render_markdown(report: EvaluationReport) -> str:
    lines = [
        "# 进球识别评估报告",
        "",
        f"- 生成时间：{report.generated_at}",
        f"- 检测器：`{report.detector}`",
        f"- 数据集：`{report.dataset}`",
        f"- 事件匹配容差：{report.tolerance_seconds:.2f}s",
        f"- 视频数：{len(report.clips)}（成功 {len(report.clips) - len(report.skipped)}，"
        f"跳过 {len(report.skipped)}）",
        "",
        "## 总计",
        "",
    ]

    if report.totals is None:
        lines.append("没有成功评估的视频。")
    else:
        totals = report.totals
        lines.extend(
            [
                "| 指标 | 值 |",
                "| --- | ---: |",
                f"| 事件 TP / FP / FN | {totals.tp} / {totals.fp} / {totals.fn} |",
                f"| 事件精确率 | {_fmt_pct(totals.precision)} |",
                f"| 事件召回率 | {_fmt_pct(totals.recall)} |",
                f"| 事件 F1 | {_fmt_num(totals.f1)} |",
                f"| 命中精确率 | {_fmt_pct(totals.made_precision)} |",
                f"| 命中召回率 | {_fmt_pct(totals.made_recall)} |",
                f"| 命中/未中分类准确率 | {_fmt_pct(totals.made_accuracy)} "
                f"({totals.made_correct}/{totals.made_evaluated}) |",
                f"| 时间误差 MAE | {_fmt_seconds(totals.time_mae)} |",
                f"| 时间误差 p95 | {_fmt_seconds(totals.time_p95)} |",
                f"| 误报/分钟 | {_fmt_num(totals.fp_per_minute)} |",
                "",
            ]
        )

    lines.extend(
        [
            "## 分视频",
            "",
            "| 视频 | 标签 | 检测 | TP | FP | FN | 精确率 | 召回率 | "
            "命中精确率 | 命中召回率 | 时间 MAE | 错误 |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | "
            "---: | ---: | ---: | --- |",
        ]
    )
    for clip in report.clips:
        if clip.metrics is None:
            lines.append(
                f"| {clip.clip} | {len(clip.labels)} | - | - | - | - | - | - | "
                f"- | - | - | {clip.error or ''} |"
            )
            continue
        metrics = clip.metrics
        lines.append(
            f"| {clip.clip} | {len(clip.labels)} | {len(clip.detections)} | "
            f"{metrics.tp} | {metrics.fp} | {metrics.fn} | "
            f"{_fmt_pct(metrics.precision)} | {_fmt_pct(metrics.recall)} | "
            f"{_fmt_pct(metrics.made_precision)} | {_fmt_pct(metrics.made_recall)} | "
            f"{_fmt_seconds(metrics.time_mae)} | |"
        )

    lines.extend(["", "## 分场景", ""])
    if not report.scenarios:
        lines.append("没有可用的场景数据。")
    else:
        lines.extend(
            [
                "| 机位 | 光照 | 视频数 | TP | FP | FN | 精确率 | 召回率 |",
                "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for scenario in report.scenarios:
            metrics = scenario.metrics
            lines.append(
                f"| {scenario.camera} | {scenario.lighting} | {scenario.clips} | "
                f"{metrics.tp} | {metrics.fp} | {metrics.fn} | "
                f"{_fmt_pct(metrics.precision)} | {_fmt_pct(metrics.recall)} |"
            )

    lines.extend(["", "## 失败明细", ""])
    failures = False
    for clip in report.clips:
        if clip.match is None:
            continue
        if not clip.match.false_positives and not clip.match.false_negatives:
            continue
        failures = True
        lines.append(f"### {clip.clip}")
        for detection in clip.match.false_positives:
            lines.append(
                f"- 误报：{detection.timestamp_seconds:.2f}s（made={detection.made}）"
            )
        for label in clip.match.false_negatives:
            lines.append(
                f"- 漏检：shot {label.shot_id} @ {label.rim_time:.2f}s"
                f"（made={label.made}）"
            )
        lines.append("")
    if not failures:
        lines.append("无。")

    if report.skipped:
        lines.extend(["", "## 跳过的视频", ""])
        for clip, error in report.skipped.items():
            lines.append(f"- `{clip}`：{error}")

    lines.extend(
        [
            "",
            "## 说明",
            "",
            "- 时间单位为秒；`rim_time` 是球心穿过筐口平面（未中时最接近筐口）的时刻。",
            "- 报告只包含统计结果，不包含视频帧或人物画面。",
            "- 分类准确率只统计匹配上的事件；命中精确率/召回率是 `made=1` 的二分类指标。",
            "- 混合场景的 `误报/分钟` 会把同一段视频的时长重复计入不同场景，仅作粗略参考。",
            "",
        ]
    )
    return "\n".join(lines)


def _fmt_pct(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.1f}%"


def _fmt_num(value: float | None, digits: int = 2) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def _fmt_seconds(value: float | None) -> str:
    return "-" if value is None else f"{value:.2f}s"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="评估进球识别：读取标注 CSV，跑检测器，输出 precision/recall 报告。"
    )
    parser.add_argument("--dataset", required=True, type=Path, help="视频所在目录")
    parser.add_argument("--labels", required=True, type=Path, help="标注 CSV 路径")
    parser.add_argument(
        "--detector",
        default="classical",
        choices=SUPPORTED_DETECTORS,
        help="检测器后端（默认 classical）",
    )
    parser.add_argument("--out", required=True, type=Path, help="报告输出目录")
    parser.add_argument(
        "--tolerance",
        type=float,
        default=DEFAULT_TOLERANCE_SECONDS,
        help=f"事件匹配容差秒数（默认 {DEFAULT_TOLERANCE_SECONDS}）",
    )
    parser.add_argument(
        "--hoop-roi",
        default=None,
        help="可选的归一化篮筐区域 x,y,width,height，覆盖 CSV 中的 hoop_roi",
    )
    parser.add_argument(
        "--no-duration",
        action="store_true",
        help="不读取视频时长（误报/分钟将为空）",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="有视频缺失或分析失败时以非 0 退出",
    )
    args = parser.parse_args(argv)

    if args.tolerance < 0:
        parser.error("--tolerance 不能为负数")
    if not args.dataset.is_dir():
        parser.error(f"--dataset 不是目录: {args.dataset}")

    try:
        labels = load_labels(args.labels)
    except (OSError, LabelError) as exc:
        parser.error(str(exc))

    try:
        hoop_roi = _parse_roi(args.hoop_roi) if args.hoop_roi else None
    except LabelError as exc:
        parser.error(str(exc))

    try:
        detector = build_detector(args.detector)
    except ValueError as exc:
        parser.error(str(exc))

    report = evaluate_dataset(
        args.dataset,
        labels,
        detector,
        tolerance_seconds=args.tolerance,
        hoop_roi=hoop_roi,
        probe_duration=not args.no_duration,
    )

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(
        json.dumps(report_to_dict(report), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.out / "report.md").write_text(render_markdown(report), encoding="utf-8")

    successful = len(report.clips) - len(report.skipped)
    print(f"评估完成：{successful}/{len(report.clips)} 个视频成功")
    if report.totals is not None:
        totals = report.totals
        print(
            f"TP={totals.tp} FP={totals.fp} FN={totals.fn} "
            f"precision={_fmt_pct(totals.precision)} recall={_fmt_pct(totals.recall)}"
        )
    print(f"报告已写入 {args.out / 'report.json'} 和 {args.out / 'report.md'}")
    if report.skipped:
        for clip, error in report.skipped.items():
            print(f"跳过 {clip}: {error}", file=sys.stderr)

    return 1 if args.strict and report.skipped else 0


if __name__ == "__main__":
    raise SystemExit(main())
