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

from dataclasses import dataclass, replace
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
    # Candidates sitting on an orange object that persists across frames (a
    # lamp post, a poster, a parked car) are static noise, not the ball.  Their
    # distance-transform peak can be large enough to win one of the eight
    # candidate slots, so they are rejected before the slot allocation.
    #
    # The second real-video verification showed that 0.50 also rejected the
    # ball while it overlapped the rim (the rim is persistent orange, so the
    # candidate's disk scored 0.5-0.92 exactly when the shot happened).  Nearly
    # constant objects sit at ~1.0, so 0.95 keeps filtering lamp posts without
    # cutting the trajectory at the rim.
    static_persistence_threshold: float = 0.95
    # Auto hoop detection is ambiguous when the winning orange region only
    # barely beats the runner-up (decorative lights, posters).  Then the
    # analysis asks for a manual hoop instead of reporting events for the wrong
    # object.  0 disables the check.
    hoop_min_confidence: float = 0.25
    # Mild prior for a centrally framed hoop: the App asks users to keep both
    # the hoop and the shooter in frame, and the real-night video picked a
    # decorative light pole in the left third over the actual rim at the centre.
    hoop_center_weight: float = 0.20
    max_track_gap: int = 5
    min_track_points: int = 5
    min_vertical_range_ratio: float = 0.04
    max_analysis_width: int = 640


@dataclass(frozen=True)
class HoopDetection:
    """Auto-detected hoop plus how clearly it beat the alternatives."""

    hoop: Hoop
    confidence: float


class ClassicalShotDetector:
    name = "classical"

    def __init__(self, config: ShotDetectionConfig | None = None) -> None:
        self.config = config or ShotDetectionConfig()

    def analyze(
        self,
        video_path: Path,
        *,
        hoop_roi: tuple[float, float, float, float] | None = None,
    ) -> AnalysisResult:
        if not video_path.exists():
            raise FileNotFoundError(f"video not found: {video_path}")

        # A user-drawn hoop region only affects this video; the rest of the
        # deployment configuration stays as-is.
        config = self.config if hoop_roi is None else replace(self.config, hoop_roi=hoop_roi)

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise VideoAnalysisError("无法打开视频文件，请确认格式受支持且文件完整。")
        # Keep analysis frames in display orientation so a ROI drawn on the
        # phone preview (which applies rotation metadata) lines up.
        capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)

        try:
            source_fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
            if not 1.0 <= source_fps <= 240.0:
                source_fps = 30.0
            sample_step = max(1, int(round(source_fps / max(config.sample_fps, 1.0))))

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

                frame = _resize_for_analysis(frame, config.max_analysis_width)
                height, width = frame.shape[:2]
                if analysis_size is None:
                    analysis_size = (width, height)
                    persistent = np.zeros((height, width), dtype=np.float32)
                elif analysis_size != (width, height):
                    frame = cv2.resize(frame, analysis_size, interpolation=cv2.INTER_AREA)

                mask = _clean_mask(_orange_mask(frame, config))
                assert persistent is not None
                if sample_frame == 0:
                    # Seed the persistence map with the first frame so static
                    # orange objects are already "persistent" before the
                    # exponential average has warmed up.  Otherwise they win
                    # every candidate slot for the first half second.
                    persistent = mask.astype(np.float32) / 255.0
                else:
                    cv2.accumulateWeighted(
                        mask.astype(np.float32) / 255.0,
                        persistent,
                        _PERSISTENCE_ALPHA,
                    )

                timestamp = source_frame / source_fps
                detections.append(
                    _ball_candidates(
                        mask,
                        persistent,
                        sample_frame=sample_frame,
                        timestamp=timestamp,
                        config=config,
                    )
                )
                sample_frame += 1
                source_frame += 1
        finally:
            capture.release()

        if analysis_size is None or persistent is None or not detections:
            raise VideoAnalysisError("视频中没有可读取的画面。")

        width, height = analysis_size
        hoop_detection = self._hoop(persistent, width=width, height=height, config=config)
        if hoop_detection is None:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note=(
                    "未检测到篮筐：当前基线需要画面中持续可见的橙色/红色篮筐，"
                    "或在确认视频时手动圈定篮筐（也可用 BASKETBALL_HOOP_ROI 指定）。"
                ),
            )
        hoop_confidence = round(hoop_detection.confidence, 3)
        if (
            config.hoop_min_confidence > 0.0
            and config.hoop_roi is None
            and hoop_detection.confidence < config.hoop_min_confidence
        ):
            # Returning events for a different orange object is worse than
            # asking for the hoop region the App already lets users draw.
            return AnalysisResult(
                detector=self.name,
                shots=[],
                hoop_confidence=hoop_confidence,
                note=(
                    "自动找筐置信度不足：画面中可能有多个橙色物体（灯柱、海报等），"
                    "无法确定真正的篮筐。请在 App 确认视频时手动圈定篮筐，"
                    "或设置 BASKETBALL_HOOP_ROI 后重试。"
                ),
            )
        hoop = hoop_detection.hoop

        tracks = build_tracks(
            detections,
            frame_width=width,
            frame_height=height,
            max_gap=config.max_track_gap,
            min_points=config.min_track_points,
            min_vertical_range_ratio=config.min_vertical_range_ratio,
        )
        if not tracks:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note="未检测到可跟踪的篮球：当前基线依赖橙色球的颜色特征。",
                hoop_confidence=hoop_confidence,
            )

        events = detect_shot_events(
            tracks,
            hoop,
            frame_height=height,
            cooldown_seconds=config.shot_cooldown_seconds,
        )
        if not events:
            return AnalysisResult(
                detector=self.name,
                shots=[],
                note="检测到篮球轨迹，但未识别到经过篮筐的投篮。",
                hoop_confidence=hoop_confidence,
            )

        shots = [
            ShotEvent(
                timestamp_seconds=round(event.timestamp, 3),
                frame_index=max(0, int(round(event.timestamp * source_fps))),
                made=event.made,
            )
            for event in events
        ]
        return AnalysisResult(
            detector=self.name,
            shots=shots,
            hoop_confidence=hoop_confidence,
        )

    def _hoop(
        self,
        persistent: np.ndarray,
        *,
        width: int,
        height: int,
        config: ShotDetectionConfig,
    ) -> HoopDetection | None:
        if config.hoop_roi is not None:
            x, y, roi_width, roi_height = config.hoop_roi
            return HoopDetection(
                hoop=Hoop(
                    center_x=(x + roi_width / 2.0) * width,
                    center_y=(y + roi_height / 2.0) * height,
                    width=roi_width * width,
                    height=roi_height * height,
                ),
                confidence=1.0,
            )
        return _find_hoop(persistent, width=width, height=height, config=config)



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
    persistent: np.ndarray,
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

    A candidate is rejected when its neighbourhood is a persistent orange
    structure.  Lamp posts, posters and other static orange objects produce a
    large distance-transform peak every frame; with only eight candidate slots
    per frame they pushed the real ball out in the real-night-video
    verification.  The ball keeps moving, so its persistence stays low except
    for the few frames it spends at the top of its arc.
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
        if _persistence_around(
            persistent,
            x=float(xs[best]),
            y=float(ys[best]),
            radius=radius,
        ) >= config.static_persistence_threshold:
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


def _persistence_around(persistent: np.ndarray, *, x: float, y: float, radius: float) -> float:
    """Mean orange persistence inside a disk centred on a candidate."""

    height, width = persistent.shape[:2]
    disk_radius = max(int(round(radius)), 2)
    x0 = max(0, int(round(x)) - disk_radius)
    x1 = min(width, int(round(x)) + disk_radius + 1)
    y0 = max(0, int(round(y)) - disk_radius)
    y1 = min(height, int(round(y)) + disk_radius + 1)
    region = persistent[y0:y1, x0:x1]
    if region.size == 0:
        return 1.0
    disk = np.zeros(region.shape, dtype=np.uint8)
    cv2.circle(
        disk,
        (int(round(x)) - x0, int(round(y)) - y0),
        disk_radius,
        1,
        thickness=-1,
    )
    values = region[disk.astype(bool)]
    if values.size == 0:
        return 1.0
    return float(values.mean())


def _find_hoop(
    persistent: np.ndarray,
    *,
    width: int,
    height: int,
    config: ShotDetectionConfig,
) -> HoopDetection | None:
    mask = (persistent >= config.hoop_persistence).astype(np.uint8) * 255
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (7, 3)),
    )

    contours, _hierarchy = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    scored: list[tuple[float, tuple[int, int, int, int]]] = []
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
        center_x = x + candidate_width / 2.0
        # The user is asked to keep the hoop and the shooter in frame, so a
        # region in the left/right third is a weaker hoop candidate than a
        # centred one.  This is a tie-breaker, not a hard rule.
        centrality = 1.0 - min(abs(center_x / width - 0.5) * 2.0, 1.0)
        center_factor = (1.0 - config.hoop_center_weight) + (
            config.hoop_center_weight * centrality
        )
        score = persistence * (0.5 + width_score) * (0.5 + position_score) * center_factor
        scored.append((score, (x, y, candidate_width, candidate_height)))

    if not scored:
        return None
    scored.sort(key=lambda item: item[0], reverse=True)
    best_score, (x, y, candidate_width, candidate_height) = scored[0]
    if len(scored) > 1:
        runner_up = scored[1][0]
        margin = (best_score - runner_up) / best_score if best_score > 0 else 0.0
        confidence = min(max(margin, 0.0), 1.0)
    else:
        confidence = 1.0
    return HoopDetection(
        hoop=Hoop(
            center_x=x + candidate_width / 2.0,
            center_y=y + candidate_height / 2.0,
            width=float(candidate_width),
            height=float(max(candidate_height, 2)),
        ),
        confidence=confidence,
    )
