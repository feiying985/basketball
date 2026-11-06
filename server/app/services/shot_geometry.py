"""Geometry rules that turn a ball trajectory into make/miss events.

The MVP uses a single camera and a visible hoop region. A shot is reported when
the tracked ball:

1. rises above the hoop,
2. descends back towards the hoop, and
3. passes near the rim.

It is a make when the descending trajectory crosses the rim plane inside the
rim opening and keeps falling; otherwise a near miss.  This mirrors the
"trajectory through the hoop" approach used by the reference projects in
``docs/research/shot-recognition.md`` without depending on a specific model.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import isfinite

from app.services.ball_tracking import BallPoint, BallTrack


@dataclass(frozen=True)
class Hoop:
    """A hoop opening in analysis-frame coordinates."""

    center_x: float
    center_y: float
    width: float
    height: float

    @property
    def half_width(self) -> float:
        return self.width / 2.0

    @property
    def half_height(self) -> float:
        return self.height / 2.0


@dataclass(frozen=True)
class DetectedShot:
    timestamp: float
    frame_index: int
    made: bool


_MIN_ARC_RATIO = 0.015
_APEX_ABOVE_RIM_RATIO = 0.01
_BELOW_RIM_RATIO = 0.01
_NEAR_RIM_WIDTH_FACTOR = 1.8
_MAKE_WIDTH_FACTOR = 0.85
_SEARCH_WINDOW = 12


def detect_shot_events(
    tracks: list[BallTrack],
    hoop: Hoop,
    *,
    frame_height: int,
    cooldown_seconds: float = 1.5,
) -> list[DetectedShot]:
    """Return deduplicated shot events for all tracks, ordered by time."""

    events: list[DetectedShot] = []
    for track in tracks:
        events.extend(_track_events(track, hoop, frame_height))
    return _deduplicate(events, cooldown_seconds)


def _track_events(track: BallTrack, hoop: Hoop, frame_height: int) -> list[DetectedShot]:
    points = track.points
    if len(points) < 5:
        return []

    ys = _smooth([point.y for point in points], window=3)
    min_arc = max(_MIN_ARC_RATIO * frame_height, 2.0)
    events: list[DetectedShot] = []

    for index in range(1, len(points) - 1):
        if not (ys[index] <= ys[index - 1] and ys[index] <= ys[index + 1]):
            continue
        # The ball must be at (or above) the rim at the apex to be a shot.
        if ys[index] > hoop.center_y - _APEX_ABOVE_RIM_RATIO * frame_height:
            continue
        # A pass or a dribble has no meaningful rise/fall around the apex.
        rise_start = max(0, index - _SEARCH_WINDOW)
        if index - rise_start < 2 or max(ys[rise_start:index]) - ys[index] < min_arc:
            continue
        # A real shot rises from below the rim. Requiring a pre-apex point below
        # the rim prevents a rim bounce (ball already above the rim, bouncing
        # up again) from being counted as a second shot.
        if max(ys[rise_start:index]) < hoop.center_y + _BELOW_RIM_RATIO * frame_height:
            continue
        fall_end = min(len(points), index + _SEARCH_WINDOW + 1)
        if fall_end - index < 2 or max(ys[index + 1 : fall_end]) - ys[index] < min_arc:
            continue
        # Shots travel towards the hoop; ignore arcs far away from it.
        if abs(points[index].x - hoop.center_x) > hoop.width * 4.0:
            continue

        event = _crossing_event(points, ys, index, fall_end, hoop, frame_height)
        if event is not None:
            events.append(event)

    return events


def _crossing_event(
    points: list[BallPoint],
    ys: list[float],
    apex: int,
    fall_end: int,
    hoop: Hoop,
    frame_height: int,
) -> DetectedShot | None:
    rim_y = hoop.center_y
    minimum_below = 0.01 * frame_height

    crossing: int | None = None
    for index in range(apex + 1, fall_end):
        if ys[index] >= rim_y:
            crossing = index
            break

    if crossing is not None:
        previous_y = ys[crossing - 1]
        current_y = ys[crossing]
        span = current_y - previous_y
        ratio = 0.0 if span <= 1e-6 else (rim_y - previous_y) / span
        ratio = min(max(ratio, 0.0), 1.0)
        x_at_rim = points[crossing - 1].x + ratio * (points[crossing].x - points[crossing - 1].x)
        distance_x = abs(x_at_rim - hoop.center_x)

        after = ys[crossing : min(len(ys), crossing + 6)]
        if not after or max(after) < rim_y + minimum_below:
            # The track ends exactly at the rim; there is not enough evidence
            # that the ball went through.
            return None
        bounced_out = min(after) < rim_y - minimum_below

        if distance_x <= hoop.half_width * _MAKE_WIDTH_FACTOR and not bounced_out:
            point = points[crossing]
            return DetectedShot(timestamp=point.timestamp, frame_index=point.frame, made=True)
        if distance_x <= hoop.half_width * _NEAR_RIM_WIDTH_FACTOR:
            point = points[crossing]
            return DetectedShot(timestamp=point.timestamp, frame_index=point.frame, made=False)

        # The ball crossed the rim plane far outside the opening. If it had
        # actually touched the rim before deflecting away, it is still a shot
        # attempt (a near miss), not a pass.
        closest = _near_rim_point(points, apex + 1, crossing, hoop, frame_height)
        if closest is not None:
            return DetectedShot(timestamp=closest.timestamp, frame_index=closest.frame, made=False)
        return None

    # No rim-plane crossing: the ball may have hit the rim and bounced away.
    # Report it as a near miss only when it actually reached rim height near
    # the hoop and then descended.
    closest = _near_rim_point(points, apex + 1, fall_end, hoop, frame_height)
    if closest is None:
        return None
    if max(ys[apex + 1 : fall_end]) < rim_y + minimum_below:
        return None
    return DetectedShot(timestamp=closest.timestamp, frame_index=closest.frame, made=False)


def _near_rim_point(
    points: list[BallPoint],
    start: int,
    end: int,
    hoop: Hoop,
    frame_height: int,
) -> BallPoint | None:
    """Return the closest point to the hoop centre within the rim band."""

    band = max(hoop.half_height, 0.03 * frame_height)
    candidates = [
        points[index]
        for index in range(start, end)
        if abs(points[index].y - hoop.center_y) <= band
    ]
    if not candidates:
        return None
    closest = min(candidates, key=lambda point: abs(point.x - hoop.center_x))
    if abs(closest.x - hoop.center_x) > hoop.half_width * _NEAR_RIM_WIDTH_FACTOR:
        return None
    return closest


def _smooth(values: list[float], window: int) -> list[float]:
    if window <= 1 or len(values) < window:
        return [float(value) for value in values]
    padding = window // 2
    padded = [values[0]] * padding + [float(value) for value in values] + [values[-1]] * padding
    smoothed = []
    for index in range(len(values)):
        chunk = padded[index : index + window]
        value = sum(chunk) / window
        smoothed.append(value if isfinite(value) else values[index])
    return smoothed


def _deduplicate(events: list[DetectedShot], cooldown_seconds: float) -> list[DetectedShot]:
    """Collapse duplicate detections of one shot and enforce the cooldown.

    Tracks can fragment around occlusions, and both the pre- and post-rim parts
    may produce an event. When two events are close in time, a make wins over a
    miss; otherwise the earlier event is kept.
    """

    ordered = sorted(events, key=lambda event: event.timestamp)
    deduplicated: list[DetectedShot] = []
    for event in ordered:
        if not deduplicated or event.timestamp - deduplicated[-1].timestamp > cooldown_seconds:
            deduplicated.append(event)
            continue
        if event.made and not deduplicated[-1].made:
            deduplicated[-1] = event
    return deduplicated
