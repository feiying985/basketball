"""Classical computer-vision shot detector.

This is the first real detector behind the ``ShotDetector`` interface. It uses
OpenCV only (no model download, no GPU) and works in three stages:

1. sample frames and build an orange-colour mask,
2. find persistent orange structures (the rim) and moving round blobs (the ball),
3. associate ball candidates into tracks and apply trajectory/hoop geometry.

Limitations are documented in ``docs/shot-detection.md``: the baseline needs a
visible orange/red rim and an orange basketball, assumes a mostly fixed camera
and single-camera 2D geometry. It is intentionally replaceable by a learned
detector later.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from app.schemas import AnalysisResult, ShotEvent
from app.services.ball_tracking import BallPoint, build_tracks
from app.services.shot_geometry import Hoop, detect_shot_events

_PERSISTENCE_ALPHA = 0.12
_MAX_BALL_CANDIDATES = 8


class VideoAnalysisError(ValueError):
    """Raised when a video cannot be decoded or analysed."""


@dataclass(frozen=True)
class ShotDetectionConfig:
    sample_fps: float = 10.0
    shot_cooldown_seconds: float = 1.5
    hoop_roi: tuple[float, float, float, float] | None = None
    ball_min_radius_ratio: float = 0.010
    ball_max_radius_ratio: float = 0.120
    hue_min: int = 0
    hue_max: int = 38
    saturation_min: int = 70
    value_min: int = 50
    hoop_persistence: float = 0.50
    hoop_min_width_ratio: float = 0.04
    hoop_max_width_ratio: float = 0.45
    hoop_max_height_ratio: float = 0.60
    hoop_top_ratio: float = 0.80
    max_track_gap: int = 5
    min_track_points: int = 5
    min_vertical_range_ratio: float = 0.04
    max_analysis_width: int = 640


class ClassicalShotDetector:
    name = "classical"

    def __init__(self, config: ShotDetectionConfig | None = None) -> None:
        self.config = config or ShotDetectionConfig()

    def analyze(self, video_path: Path) -> AnalysisResult:
        if not video_path.exists():
            raise FileNotFoundError(f"video not found: {video_path}")

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise VideoAnalysisError("无法打开视频文件，请确认格式受支持且文件完整。")

        try:
            source_fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
            if not 1.0 <= source_fps <= 240.0:
                source_fps = 30.0
            sample_step = max(1, int(round(source_fps / max(self.config.sample_fps, 1.0))))

            detections: list[list[BallPoint]] = []
            persistent: np.ndarray | None = None
            analysis_size: tuple[int, int] | None = None
            source_frame = 0
            sample_frame = 0

            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                if source_frame % sample_step != 0:
                    source_frame += 1
                    continue

                frame = _resize_for_analysis(frame, self.config.max_analysis_width)
                height, width = frame.shape[:2]
                if analysis_size is None:
                    analysis_size = (width, height)
                    persistent = np.zeros((height, width), dtype=np.float32)
                elif analysis_size != (width, height):
                    frame = cv2.resize(frame, analysis_size, interpolation=cv2.INTER_AREA)

                mask = _clean_mask(_orange_mask(frame, self.config))
                assert persistent is not None
                cv2.accumulateWeighted(mask.astype(np.float32) / 255.0, persistent, _PERSISTENCE_ALPHA)

                timestamp = source_frame / source_fps
                detections.append(
                    _ball_candidates(
                        mask,
                        sample_frame=sample_frame,
                        timestamp=timestamp,
                        config=self.config,
                    )
                )
                sample_frame += 1
                source_frame += 1
        finally:
            capture.release()

        if analysis_size is None or persistent is None or not detections:
            raise VideoAnalysisError("视频中没有可读取的画面。")

        width, height = analysis_size
        hoop = self._hoop(persistent, width=width, height=height)
        if hoop is None:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note=(
                    "未检测到篮筐：当前基线需要画面中持续可见的橙色/红色篮筐，"
                    "或通过 BASKETBALL_HOOP_ROI 指定篮筐区域。"
                ),
            )

        tracks = build_tracks(
            detections,
            frame_width=width,
            frame_height=height,
            max_gap=self.config.max_track_gap,
            min_points=self.config.min_track_points,
            min_vertical_range_ratio=self.config.min_vertical_range_ratio,
        )
        if not tracks:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note="未检测到可跟踪的篮球：当前基线依赖橙色球的颜色特征。",
            )

        events = detect_shot_events(
            tracks,
            hoop,
            frame_height=height,
            cooldown_seconds=self.config.shot_cooldown_seconds,
        )
        if not events:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note="检测到篮球轨迹，但未识别到经过篮筐的投篮。",
            )

        shots = [
            ShotEvent(
                timestamp_seconds=round(event.timestamp, 3),
                frame_index=max(0, int(round(event.timestamp * source_fps))),
                made=event.made,
            )
            for event in events
        ]
        return AnalysisResult(detector=self.name, shots=shots)

    def _hoop(self, persistent: np.ndarray, *, width: int, height: int) -> Hoop | None:
        if self.config.hoop_roi is not None:
            x, y, roi_width, roi_height = self.config.hoop_roi
            return Hoop(
                center_x=(x + roi_width / 2.0) * width,
                center_y=(y + roi_height / 2.0) * height,
                width=roi_width * width,
                height=roi_height * height,
            )
        return _find_hoop(persistent, width=width, height=height, config=self.config)


def _resize_for_analysis(frame: np.ndarray, max_width: int) -> np.ndarray:
    height, width = frame.shape[:2]
    if width <= max_width:
        return frame
    scale = max_width / width
    return cv2.resize(
        frame,
        (max_width, max(1, int(round(height * scale)))),
        interpolation=cv2.INTER_AREA,
    )


def _orange_mask(frame: np.ndarray, config: ShotDetectionConfig) -> np.ndarray:
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    lower = np.array([config.hue_min, config.saturation_min, config.value_min], dtype=np.uint8)
    upper = np.array([config.hue_max, 255, 255], dtype=np.uint8)
    return cv2.inRange(hsv, lower, upper)


def _clean_mask(mask: np.ndarray) -> np.ndarray:
    close_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    open_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    cleaned = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, close_kernel)
    return cv2.morphologyEx(cleaned, cv2.MORPH_OPEN, open_kernel)


def _ball_candidates(
    mask: np.ndarray,
    *,
    sample_frame: int,
    timestamp: float,
    config: ShotDetectionConfig,
) -> list[BallPoint]:
    """Find round orange blobs using distance-transform maxima.

    The distance transform finds thick round regions, so the ball is still
    found while it visually touches the thin rim. A plain contour scan would
    merge both into one wide contour and lose the ball exactly at the moment it
    matters most.
    """

    height, width = mask.shape[:2]
    minimum_radius = max(config.ball_min_radius_ratio * min(height, width), 2.0)
    maximum_radius = config.ball_max_radius_ratio * min(height, width)

    distance = cv2.distanceTransform(mask, cv2.DIST_L2, 5)
    kernel_size = max(5, 2 * int(round(minimum_radius)) + 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    local_maxima = cv2.dilate(distance, kernel)
    peaks = ((distance >= local_maxima - 1e-3) & (distance >= minimum_radius)).astype(np.uint8)

    count, labels, _stats, _centroids = cv2.connectedComponentsWithStats(peaks, connectivity=8)
    candidates: list[BallPoint] = []
    for label in range(1, count):
        ys, xs = np.where(labels == label)
        if xs.size == 0:
            continue
        values = distance[ys, xs]
        best = int(np.argmax(values))
        radius = float(values[best])
        if radius > maximum_radius:
            continue
        candidates.append(
            BallPoint(
                frame=sample_frame,
                timestamp=timestamp,
                x=float(xs[best]),
                y=float(ys[best]),
                radius=radius,
            )
        )

    candidates.sort(key=lambda candidate: candidate.radius, reverse=True)
    return candidates[:_MAX_BALL_CANDIDATES]


def _find_hoop(
    persistent: np.ndarray,
    *,
    width: int,
    height: int,
    config: ShotDetectionConfig,
) -> Hoop | None:
    mask = (persistent >= config.hoop_persistence).astype(np.uint8) * 255
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (7, 3)),
    )

    contours, _hierarchy = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best: tuple[float, tuple[int, int, int, int]] | None = None
    for contour in contours:
        area = cv2.contourArea(contour)
        if area < 0.0004 * width * height:
            continue
        x, y, candidate_width, candidate_height = cv2.boundingRect(contour)
        if not config.hoop_min_width_ratio * width <= candidate_width <= config.hoop_max_width_ratio * width:
            continue
        if candidate_height > config.hoop_max_height_ratio * candidate_width:
            continue
        center_y = y + candidate_height / 2.0
        if center_y > config.hoop_top_ratio * height:
            continue
        region = persistent[y : y + candidate_height, x : x + candidate_width]
        if region.size == 0:
            continue
        persistence = float(region.mean())
        width_score = min(candidate_width / (0.15 * width), 1.5)
        position_score = 1.0 - 0.5 * abs(center_y / height - 0.3)
        score = persistence * (0.5 + width_score) * (0.5 + position_score)
        if best is None or score > best[0]:
            best = (score, (x, y, candidate_width, candidate_height))

    if best is None:
        return None
    x, y, candidate_width, candidate_height = best[1]
    return Hoop(
        center_x=x + candidate_width / 2.0,
        center_y=y + candidate_height / 2.0,
        width=float(candidate_width),
        height=float(max(candidate_height, 2)),
    )
