"""Tests for candidate association and track filtering."""

from __future__ import annotations

from app.services.ball_tracking import BallPoint, build_tracks


def _point(frame: int, x: float, y: float, radius: float = 12.0) -> BallPoint:
    return BallPoint(frame=frame, timestamp=frame / 10.0, x=x, y=y, radius=radius)


def test_associates_moving_ball_into_one_track() -> None:
    detections = [
        [_point(frame, 320, 300 - frame * 10)]
        for frame in range(10)
    ]

    tracks = build_tracks(
        detections,
        frame_width=640,
        frame_height=360,
        min_points=5,
        min_vertical_range_ratio=0.04,
    )

    assert len(tracks) == 1
    assert len(tracks[0].points) == 10


def test_bridges_short_gap() -> None:
    detections = [
        [_point(frame, 320, 300 - frame * 10)] if frame != 4 else []
        for frame in range(10)
    ]

    tracks = build_tracks(
        detections,
        frame_width=640,
        frame_height=360,
        max_gap=2,
        min_points=5,
        min_vertical_range_ratio=0.04,
    )

    assert len(tracks) == 1
    assert len(tracks[0].points) == 9


def test_static_orange_object_is_filtered_out() -> None:
    detections = [[_point(frame, 320, 100, radius=4.0)] for frame in range(10)]

    tracks = build_tracks(
        detections,
        frame_width=640,
        frame_height=360,
        min_points=5,
        min_vertical_range_ratio=0.04,
    )

    assert tracks == []
