"""End-to-end tests for the classical detector using synthetic videos."""

from __future__ import annotations

from pathlib import Path

import pytest

from app.services.classical_shot_detection import (
    ClassicalShotDetector,
    ShotDetectionConfig,
    VideoAnalysisError,
)
from tests.synthetic import write_dribble_video, write_shot_video


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


def test_detects_near_miss(tmp_path: Path) -> None:
    video = tmp_path / "miss.avi"
    write_shot_video(video, apex_x=390.0, end_x=390.0)

    result = _detector().analyze(video)

    assert result.shots
    assert all(not shot.made for shot in result.shots)


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


def test_unreadable_video_raises(tmp_path: Path) -> None:
    video = tmp_path / "broken.mp4"
    video.write_bytes(b"not a video")

    with pytest.raises(VideoAnalysisError):
        _detector().analyze(video)


def test_missing_file_raises(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        _detector().analyze(tmp_path / "missing.mp4")
