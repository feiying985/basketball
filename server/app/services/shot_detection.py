"""Shot detection abstraction and detector factory.

The default implementation is the classical OpenCV baseline in
``app.services.classical_shot_detection``. A stub detector remains available
for plumbing tests and for running the API without video dependencies:

    BASKETBALL_DETECTOR=stub uvicorn app.main:app

A learned detector (for example YOLO + ByteTrack) can replace the classical one
behind the same ``ShotDetector`` interface.
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

from app.config import settings
from app.schemas import AnalysisResult


HoopRoi = tuple[float, float, float, float]


class ShotDetector(Protocol):
    name: str

    def analyze(
        self,
        video_path: Path,
        *,
        hoop_roi: HoopRoi | None = None,
    ) -> AnalysisResult:
        """Analyze a video and return the detected shot events.

        ``hoop_roi`` is an optional normalized ``x,y,width,height`` region
        chosen by the user; it overrides the deployment-wide ROI for this run.
        """


class StubShotDetector:
    name = "stub"

    def analyze(
        self,
        video_path: Path,
        *,
        hoop_roi: HoopRoi | None = None,
    ) -> AnalysisResult:
        if not video_path.exists():
            raise FileNotFoundError(f"video not found: {video_path}")
        return AnalysisResult(
            detector=self.name,
            shots=[],
            note="占位检测器：未接入识别逻辑，未产生识别结果。",
        )


def create_detector() -> ShotDetector:
    backend = settings.detector_backend
    if backend in {"stub", "none"}:
        return StubShotDetector()
    if backend in {"classical", "opencv"}:
        from app.services.classical_shot_detection import (
            ClassicalShotDetector,
            ShotDetectionConfig,
        )

        return ClassicalShotDetector(
            ShotDetectionConfig(
                sample_fps=settings.sample_fps,
                shot_cooldown_seconds=settings.shot_cooldown_seconds,
                hoop_roi=settings.hoop_roi,
                hue_min=settings.hue_min,
                hue_max=settings.hue_max,
                saturation_min=settings.saturation_min,
                value_min=settings.value_min,
                ball_min_radius_ratio=settings.ball_min_radius_ratio,
                ball_max_radius_ratio=settings.ball_max_radius_ratio,
                static_persistence_threshold=settings.static_persistence_threshold,
                hoop_min_confidence=settings.hoop_min_confidence,
            )
        )
    raise ValueError(
        f"未知的检测器后端: {backend!r}；可选值: classical, stub"
    )


detector: ShotDetector = create_detector()
