"""Tests for the local evaluation tooling (no real videos required)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.schemas import AnalysisResult, ShotEvent
from tools.evaluate import (
    HoopRoi,
    Label,
    LabelError,
    build_detector,
    evaluate_dataset,
    load_labels,
    match_events,
    render_markdown,
    report_to_dict,
)


def _shot(timestamp: float, *, made: bool = True) -> ShotEvent:
    return ShotEvent(timestamp_seconds=timestamp, frame_index=0, made=made)


def _label(
    *,
    clip: str = "clip.mp4",
    shot_id: int = 1,
    rim_time: float = 2.0,
    made: bool = True,
    **kwargs: object,
) -> Label:
    return Label(clip=clip, shot_id=shot_id, rim_time=rim_time, made=made, **kwargs)


class FakeDetector:
    name = "fake"

    def __init__(self, shots_by_clip: dict[str, list[ShotEvent]]) -> None:
        self.shots_by_clip = shots_by_clip
        self.calls: list[tuple[Path, HoopRoi | None]] = []

    def analyze(
        self,
        video_path: Path,
        *,
        hoop_roi: HoopRoi | None = None,
    ) -> AnalysisResult:
        self.calls.append((video_path, hoop_roi))
        return AnalysisResult(
            detector=self.name,
            shots=self.shots_by_clip.get(video_path.name, []),
        )


def _write_labels(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8-sig")


def test_load_labels_parses_required_and_optional_fields(tmp_path: Path) -> None:
    labels_file = tmp_path / "labels.csv"
    _write_labels(
        labels_file,
        "clip,shot_id,rim_time,made,release_time,occluded,camera,lighting,hoop_roi,notes\n"
        'A.mp4,1,4.1,1,3.2,0,side,day,"0.4,0.2,0.2,0.04",擦筐\n'
        "A.mp4,2,9.6,0,,1,side,day,,三不沾\n",
    )

    labels = load_labels(labels_file)

    assert len(labels) == 2
    first, second = labels
    assert first.clip == "A.mp4"
    assert first.shot_id == 1
    assert first.rim_time == pytest.approx(4.1)
    assert first.made is True
    assert first.release_time == pytest.approx(3.2)
    assert first.occluded is False
    assert first.camera == "side"
    assert first.lighting == "day"
    assert first.hoop_roi == pytest.approx((0.4, 0.2, 0.2, 0.04))
    assert first.notes == "擦筐"
    assert second.release_time is None
    assert second.occluded is True
    assert second.made is False


@pytest.mark.parametrize(
    "text",
    [
        "clip,shot_id,rim_time\nA.mp4,1,4.1\n",
        "clip,shot_id,rim_time,made\nA.mp4,1,4.1,maybe\n",
        "clip,shot_id,rim_time,made\nA.mp4,1,-1,1\n",
        "clip,shot_id,rim_time,made\nA.mp4,1,4.1,1\nA.mp4,1,5.0,1\n",
    ],
)
def test_load_labels_rejects_malformed_rows(tmp_path: Path, text: str) -> None:
    labels_file = tmp_path / "labels.csv"
    _write_labels(labels_file, text)

    with pytest.raises(LabelError):
        load_labels(labels_file)


def test_match_events_is_one_to_one_and_respects_tolerance() -> None:
    detections = [_shot(1.0), _shot(1.6), _shot(10.0)]
    labels = [_label(shot_id=1, rim_time=1.1), _label(shot_id=2, rim_time=10.4)]

    match = match_events(detections, labels, tolerance_seconds=0.5)

    assert len(match.pairs) == 2
    assert match.pairs[0][0].timestamp_seconds == pytest.approx(1.0)
    assert match.pairs[0][1].shot_id == 1
    assert match.pairs[1][0].timestamp_seconds == pytest.approx(10.0)
    assert [shot.timestamp_seconds for shot in match.false_positives] == [1.6]
    assert match.false_negatives == ()


def test_match_events_reports_unmatched_events() -> None:
    detections = [_shot(1.0), _shot(1.3)]
    labels = [_label(shot_id=1, rim_time=4.0)]

    match = match_events(detections, labels, tolerance_seconds=0.5)

    assert match.pairs == ()
    assert len(match.false_positives) == 2
    assert len(match.false_negatives) == 1


def test_evaluate_dataset_with_fake_detector(tmp_path: Path) -> None:
    dataset = tmp_path / "data"
    dataset.mkdir()
    (dataset / "clip.mp4").write_bytes(b"not really a video")

    labels = [
        _label(shot_id=1, rim_time=2.0, made=True),
        _label(shot_id=2, rim_time=8.0, made=False),
    ]
    detector = FakeDetector(
        {
            "clip.mp4": [
                _shot(2.05, made=True),
                _shot(5.0, made=True),
                _shot(8.1, made=False),
            ]
        }
    )

    report = evaluate_dataset(
        dataset,
        labels,
        detector,
        tolerance_seconds=0.5,
        probe_duration=False,
    )

    assert report.detector == "fake"
    assert report.skipped == {}
    assert len(report.clips) == 1
    assert report.totals is not None
    totals = report.totals
    assert (totals.tp, totals.fp, totals.fn) == (2, 1, 0)
    assert totals.precision == pytest.approx(2 / 3)
    assert totals.recall == pytest.approx(1.0)
    assert (totals.made_tp, totals.made_fp, totals.made_fn) == (1, 1, 0)
    assert totals.made_precision == pytest.approx(0.5)
    assert totals.made_recall == pytest.approx(1.0)
    assert totals.made_correct == 2
    assert totals.made_accuracy == pytest.approx(1.0)
    assert totals.time_mae == pytest.approx(0.075)
    assert totals.time_p95 == pytest.approx(0.1)
    assert totals.fp_per_minute is None
    assert detector.calls[0][0].name == "clip.mp4"


def test_evaluate_dataset_records_missing_video(tmp_path: Path) -> None:
    detector = FakeDetector({})

    report = evaluate_dataset(
        tmp_path,
        [_label(clip="missing.mp4")],
        detector,
        probe_duration=False,
    )

    assert report.totals is None
    assert "missing.mp4" in report.skipped
    assert report.clips[0].metrics is None
    assert "视频不存在" in (report.clips[0].error or "")
    assert detector.calls == []


def test_hoop_roi_is_forwarded_to_detector(tmp_path: Path) -> None:
    dataset = tmp_path / "data"
    dataset.mkdir()
    (dataset / "clip.mp4").write_bytes(b"x")
    detector = FakeDetector({"clip.mp4": []})
    roi = (0.4, 0.2, 0.2, 0.04)

    evaluate_dataset(
        dataset,
        [_label(hoop_roi=roi)],
        detector,
        probe_duration=False,
    )

    assert detector.calls[0][1] == pytest.approx(roi)


def test_render_markdown_and_json_are_complete(tmp_path: Path) -> None:
    dataset = tmp_path / "data"
    dataset.mkdir()
    (dataset / "clip.mp4").write_bytes(b"x")
    detector = FakeDetector({"clip.mp4": [_shot(2.05, made=True)]})
    report = evaluate_dataset(
        dataset,
        [_label(rim_time=2.0, made=True)],
        detector,
        probe_duration=False,
    )

    markdown = render_markdown(report)
    assert "# 进球识别评估报告" in markdown
    assert "## 总计" in markdown
    assert "## 分视频" in markdown
    assert "100.0%" in markdown

    payload = report_to_dict(report)
    assert payload["detector"] == "fake"
    assert payload["totals"]["tp"] == 1  # type: ignore[index]
    assert json.dumps(payload, ensure_ascii=False)


def test_build_detector_accepts_explicit_backend() -> None:
    assert build_detector("stub").name == "stub"


def test_build_detector_rejects_unknown_backend() -> None:
    with pytest.raises(ValueError):
        build_detector("does-not-exist")
