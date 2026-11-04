"""Shot detection abstraction.

The MVP deliberately ships a placeholder detector. It returns no shots so
that upload/job plumbing can be tested without pretending that recognition
already works. A real implementation (YOLO + tracking, see
docs/research/shot-recognition.md) replaces ``StubShotDetector`` behind the
same interface.
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

from app.schemas import AnalysisResult


class ShotDetector(Protocol):
    name: str

    def analyze(self, video_path: Path) -> AnalysisResult:
        """Analyze a video and return the detected shot events."""


class StubShotDetector:
    name = "stub"

    def analyze(self, video_path: Path) -> AnalysisResult:
        if not video_path.exists():
            raise FileNotFoundError(f"video not found: {video_path}")
        return AnalysisResult(
            detector=self.name,
            shots=[],
            note="占位检测器：尚未接入 CV 模型，未产生识别结果。",
        )


detector: ShotDetector = StubShotDetector()
