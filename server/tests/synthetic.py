"""Synthetic basketball videos used by the test-suite.

The clips are deliberately simple: a static orange rim and a moving orange ball
on a dark background. They exercise the full detector (decoding, colour mask,
hoop detection, tracking and trajectory geometry) without shipping a real
basketball video or a model file in the repository.
"""

from __future__ import annotations

import math
from pathlib import Path

import cv2
import numpy as np

WIDTH = 640
HEIGHT = 360
FPS = 30
BALL_RADIUS = 12
BALL_COLOR = (0, 140, 255)
RIM_COLOR = (0, 140, 255)
BACKGROUND = (35, 35, 35)
RIM_CENTER = (320, 100)
RIM_WIDTH = 120


def write_shot_video(
    path: Path,
    *,
    apex_x: float = 320.0,
    end_x: float = 320.0,
    duration_seconds: float = 2.0,
    with_rim: bool = True,
    rim_color: tuple[int, int, int] = RIM_COLOR,
) -> None:
    """Write a clip with one shot arc.

    The ball starts at the bottom, peaks above the rim and falls back to the
    starting height. ``apex_x``/``end_x`` control whether the arc goes through
    the rim or misses to the side.
    """

    frames = max(int(FPS * duration_seconds), 2)
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"MJPG"),
        FPS,
        (WIDTH, HEIGHT),
    )
    if not writer.isOpened():
        raise RuntimeError(f"could not open video writer for {path}")
    try:
        for index in range(frames):
            u = index / (frames - 1)
            frame = _background()
            if with_rim:
                _draw_rim(frame, rim_color)
            _draw_ball(frame, _arc_position(u, apex_x=apex_x, end_x=end_x))
            writer.write(frame)
    finally:
        writer.release()


def write_dribble_video(path: Path, *, duration_seconds: float = 2.0) -> None:
    """Write a clip where the ball stays well below the rim."""

    frames = max(int(FPS * duration_seconds), 2)
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"MJPG"),
        FPS,
        (WIDTH, HEIGHT),
    )
    if not writer.isOpened():
        raise RuntimeError(f"could not open video writer for {path}")
    try:
        for index in range(frames):
            u = index / (frames - 1)
            y = 310 + 25 * math.sin(2 * math.pi * u * 3) ** 2
            frame = _background()
            _draw_rim(frame, RIM_COLOR)
            _draw_ball(frame, (320, y))
            writer.write(frame)
    finally:
        writer.release()


def _arc_position(u: float, *, apex_x: float, end_x: float) -> tuple[float, float]:
    # Quadratic arc: starts and ends at y=300, apex at y=55 (above the rim).
    t = 2 * u - 1
    y = 55 + (300 - 55) * t * t
    x = apex_x + (320 - apex_x) * (1 - u) * (1 - u) + (end_x - apex_x) * u * u
    return x, y


def _background() -> np.ndarray:
    return np.full((HEIGHT, WIDTH, 3), BACKGROUND, dtype=np.uint8)


def _draw_rim(frame: np.ndarray, color: tuple[int, int, int]) -> None:
    left = RIM_CENTER[0] - RIM_WIDTH // 2
    right = RIM_CENTER[0] + RIM_WIDTH // 2
    top = RIM_CENTER[1] - 4
    bottom = RIM_CENTER[1] + 4
    cv2.rectangle(frame, (left, top), (right, bottom), color, -1)


def _draw_ball(frame: np.ndarray, position: tuple[float, float]) -> None:
    center = (int(round(position[0])), int(round(position[1])))
    cv2.circle(frame, center, BALL_RADIUS, BALL_COLOR, -1)
