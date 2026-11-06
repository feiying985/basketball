"""End-to-end tests for the classical detector using synthetic videos."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.classical_shot_detection import (
    ClassicalShotDetector,
    ShotDetectionConfig,
    VideoAnalysisError,
    _ball_candidates,
)
from tests.synthetic import write_arc_video, write_dribble_video, write_shot_video


def _detector(**overrides: object) -> ClassicalShotDetector:
    return ClassicalShotDetector(
        ShotDetectionConfig(
            sample_fps=10.0,
            shot_cooldown_seconds=0.5,
            **overrides,
        )
    )


def test_detects_made_shot(tmp_path: Path) -> None:
    video = tmp_path / "make.avi"
    write_shot_video(video)

    result = _detector().analyze(video)

    assert result.detector == "classical"
    assert any(shot.made for shot in result.shots)
    made = next(shot for shot in result.shots if shot.made)
    assert 0.0 < made.timestamp_seconds < 2.0
    assert made.frame_index >= 0
    # A single clear rim is unambiguous.
    assert result.hoop_confidence == 1.0


def test_detects_near_miss(tmp_path: Path) -> None:
    video = tmp_path / "miss.avi"
    write_shot_video(video, apex_x=390.0, end_x=390.0)

    result = _detector().analyze(video)

    assert result.shots
    assert all(not shot.made for shot in result.shots)


def test_detects_two_shots_in_one_video(tmp_path: Path) -> None:
    video = tmp_path / "two-shots.avi"
    write_arc_video(video, arcs=[(320.0, 320.0), (390.0, 390.0)])

    result = _detector().analyze(video)

    assert [shot.made for shot in result.shots] == [True, False]


def test_explains_missing_hoop(tmp_path: Path) -> None:
    video = tmp_path / "no-rim.avi"
    write_shot_video(video, with_rim=False)

    result = _detector().analyze(video)

    assert result.shots == []
    assert result.note is not None
    assert "篮筐" in result.note


def test_hoop_roi_handles_non_orange_rim(tmp_path: Path) -> None:
    video = tmp_path / "white-rim.avi"
    write_shot_video(video, rim_color=(240, 240, 240))
    detector = _detector(hoop_roi=(0.40, 0.26, 0.20, 0.04))

    result = detector.analyze(video)

    assert any(shot.made for shot in result.shots)


def test_dribble_produces_no_shots(tmp_path: Path) -> None:
    video = tmp_path / "dribble.avi"
    write_dribble_video(video)

    result = _detector().analyze(video)

    assert result.shots == []
    assert result.note is not None


def test_static_orange_noise_does_not_hide_the_ball(tmp_path: Path) -> None:
    video = tmp_path / "static-noise.avi"
    write_shot_video(video, static_blobs=10)

    result = _detector().analyze(video)

    assert any(shot.made for shot in result.shots)


def test_without_static_filter_the_noise_hides_the_ball(tmp_path: Path) -> None:
    """Document the failure mode that motivated the persistence filter."""

    video = tmp_path / "static-noise-unfiltered.avi"
    write_shot_video(video, static_blobs=10)
    detector = _detector(static_persistence_threshold=1.1)

    result = detector.analyze(video)

    assert result.shots == []


def test_static_filter_keeps_ball_that_overlaps_the_rim() -> None:
    """Regression for the second real-video report.

    While the ball overlaps the persistent orange rim, its candidate disk also
    scores high persistence.  The old 0.50 default rejected exactly those
    candidates and broke the trajectory at the rim; the 0.95 default keeps them
    because a truly constant object (lamp post) sits at ~1.0.
    """

    height, width = 360, 640
    mask = np.zeros((height, width), dtype=np.uint8)
    cv2.rectangle(mask, (260, 96), (380, 108), 255, -1)  # thick persistent rim
    cv2.circle(mask, (320, 102), 12, 255, -1)  # ball touching the rim
    persistent = np.zeros((height, width), dtype=np.float32)
    cv2.rectangle(persistent, (260, 96), (380, 108), 1.0, -1)

    default = _ball_candidates(
        mask,
        persistent,
        sample_frame=5,
        timestamp=0.5,
        config=ShotDetectionConfig(),
    )
    assert any(
        abs(candidate.x - 320) <= 2 and abs(candidate.y - 102) <= 2
        for candidate in default
    )

    aggressive = replace(ShotDetectionConfig(), static_persistence_threshold=0.50)
    rejected = _ball_candidates(
        mask,
        persistent,
        sample_frame=5,
        timestamp=0.5,
        config=aggressive,
    )
    assert all(
        abs(candidate.x - 320) > 2 or abs(candidate.y - 102) > 2
        for candidate in rejected
    )


def test_ambiguous_auto_hoop_requires_manual_roi(tmp_path: Path) -> None:
    """An orange bar next to the rim makes auto hoop detection ambiguous."""

    video = tmp_path / "competing-hoop.avi"
    write_shot_video(video, competing_hoop=True)

    result = _detector().analyze(video)

    assert result.shots == []
    assert result.note is not None
    assert "手动" in result.note
    assert result.hoop_confidence is not None
    assert result.hoop_confidence < ShotDetectionConfig().hoop_min_confidence

    manual = _detector().analyze(video, hoop_roi=(0.40, 0.26, 0.20, 0.04))

    assert any(shot.made for shot in manual.shots)
    assert manual.hoop_confidence == 1.0


def test_auto_hoop_confidence_can_be_disabled(tmp_path: Path) -> None:
    video = tmp_path / "competing-hoop-disabled.avi"
    write_shot_video(video, competing_hoop=True)
    detector = _detector(hoop_min_confidence=0.0)

    result = detector.analyze(video)

    assert any(shot.made for shot in result.shots)
    assert result.hoop_confidence is not None
    assert result.hoop_confidence < 1.0


def test_per_request_hoop_roi_overrides_environment(tmp_path: Path) -> None:
    video = tmp_path / "white-rim-roi.avi"
    write_shot_video(video, rim_color=(240, 240, 240))

    result = _detector().analyze(
        video,
        hoop_roi=(0.40, 0.26, 0.20, 0.04),
    )

    assert any(shot.made for shot in result.shots)


def test_unreadable_video_raises(tmp_path: Path) -> None:
    video = tmp_path / "broken.mp4"
    video.write_bytes(b"not a video")

    with pytest.raises(VideoAnalysisError):
        _detector().analyze(video)


def test_missing_file_raises(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        _detector().analyze(tmp_path / "missing.mp4")
