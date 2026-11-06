"""Unit tests for the pure trajectory geometry."""

from __future__ import annotations

from app.services.ball_tracking import BallPoint, BallTrack
from app.services.shot_geometry import Hoop, detect_shot_events

HOOP = Hoop(center_x=320, center_y=100, width=120, height=10)
FRAME_HEIGHT = 360


def _track(xs: list[float], ys: list[float], *, fps: float = 10.0) -> BallTrack:
    return BallTrack(
        points=[
            BallPoint(frame=index, timestamp=index / fps, x=float(x), y=float(y), radius=12.0)
            for index, (x, y) in enumerate(zip(xs, ys, strict=True))
        ]
    )


def _arc(x_apex: float, *, samples: int = 20) -> tuple[list[float], list[float]]:
    xs: list[float] = []
    ys: list[float] = []
    for index in range(samples):
        u = index / (samples - 1)
        t = 2 * u - 1
        ys.append(55 + (300 - 55) * t * t)
        xs.append(x_apex)
    return xs, ys


def _miss_xs(apex: float = 390.0, *, samples: int = 20) -> list[float]:
    return [
        apex + (320 - apex) * (1 - index / (samples - 1)) ** 2
        for index in range(samples)
    ]


def test_made_shot() -> None:
    xs, ys = _arc(320)
    events = detect_shot_events([_track(xs, ys)], HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0)

    assert len(events) == 1
    assert events[0].made is True


def test_near_miss() -> None:
    _, ys = _arc(320)
    events = detect_shot_events(
        [_track(_miss_xs(), ys)], HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0
    )

    assert len(events) == 1
    assert events[0].made is False


def test_dribble_is_ignored() -> None:
    ys = [310, 320, 330, 335, 330, 320, 310, 300, 310, 320, 330, 335, 330, 320, 310, 300]
    xs = [320.0] * len(ys)
    events = detect_shot_events([_track(xs, ys)], HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0)

    assert events == []


def test_rim_bounce_is_a_miss() -> None:
    ys = [
        300, 280, 240, 190, 140, 100, 80, 65, 58, 55, 55, 58,
        65, 80, 95, 90, 80, 70, 62, 58, 60, 80, 150, 300,
    ]
    # The ball hits the rim and deflects to the right instead of falling
    # through the opening.
    xs = [320.0] * 14 + [320, 330, 350, 380, 420, 460, 500, 540, 580, 620]
    events = detect_shot_events([_track(xs, ys)], HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0)

    assert len(events) == 1
    assert events[0].made is False


def test_duplicate_tracks_are_deduplicated_and_make_wins() -> None:
    made_xs, made_ys = _arc(320)
    miss_ys = list(made_ys)
    tracks = [_track(_miss_xs(), miss_ys), _track(made_xs, made_ys)]

    events = detect_shot_events(tracks, HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0)

    assert len(events) == 1
    assert events[0].made is True


def test_cooldown_collapses_two_close_shots() -> None:
    xs, ys = _arc(320)
    first = _track(xs, ys)
    second_points = [
        BallPoint(
            frame=point.frame + 3,
            timestamp=point.timestamp + 0.3,
            x=point.x,
            y=point.y,
            radius=point.radius,
        )
        for point in first.points
    ]
    events = detect_shot_events(
        [first, BallTrack(points=second_points)],
        HOOP,
        frame_height=FRAME_HEIGHT,
        cooldown_seconds=1.0,
    )

    assert len(events) == 1


def test_wide_arc_is_not_a_shot() -> None:
    xs = [320 + 300 * (index / 19) for index in range(20)]
    _, ys = _arc(320)
    events = detect_shot_events([_track(xs, ys)], HOOP, frame_height=FRAME_HEIGHT, cooldown_seconds=1.0)

    assert events == []
