"""Lightweight multi-frame ball tracking.

The tracker is deliberately small: at the analysis frame rate (10 FPS by
default) a basketball moves a predictable distance between samples, so greedy
nearest-neighbour association with a constant-velocity prediction is enough.
No external tracking library is needed for the MVP.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from math import hypot, log


@dataclass(frozen=True)
class BallPoint:
    """A candidate ball centre in analysis-frame coordinates."""

    frame: int
    timestamp: float
    x: float
    y: float
    radius: float


@dataclass
class BallTrack:
    """A sequence of associated ball candidates."""

    points: list[BallPoint] = field(default_factory=list)
    misses: int = 0

    @property
    def last(self) -> BallPoint:
        return self.points[-1]

    def velocity(self) -> tuple[float, float]:
        """Return the current per-frame velocity estimate."""

        if len(self.points) < 2:
            return 0.0, 0.0
        previous, last = self.points[-2], self.points[-1]
        delta = max(last.frame - previous.frame, 1)
        return (last.x - previous.x) / delta, (last.y - previous.y) / delta

    def predict(self, frame: int) -> tuple[float, float]:
        vx, vy = self.velocity()
        steps = max(frame - self.last.frame, 0)
        return self.last.x + vx * steps, self.last.y + vy * steps

    def add(self, point: BallPoint) -> None:
        self.points.append(point)
        self.misses = 0


def build_tracks(
    detections: list[list[BallPoint]],
    *,
    frame_width: int,
    frame_height: int,
    max_gap: int = 5,
    min_points: int = 5,
    min_vertical_range_ratio: float = 0.04,
) -> list[BallTrack]:
    """Associate per-frame candidates into tracks and keep shot-like ones.

    ``detections[i]`` holds the candidates found in analysis frame ``i``.
    A track survives up to ``max_gap`` frames without a detection, which covers
    motion blur and short occlusions (for example while the ball passes the rim).
    Static orange objects (the rim itself, clothing) end up as tracks with almost
    no vertical movement and are discarded.
    """

    active: list[BallTrack] = []
    finished: list[BallTrack] = []
    gate = max(0.12 * frame_width, 24.0)

    for frame, candidates in enumerate(detections):
        for track in active:
            track.misses += 1

        pairs: list[tuple[float, int, int]] = []
        for track_index, track in enumerate(active):
            predicted_x, predicted_y = track.predict(frame)
            track_gate = max(gate, 3.5 * track.last.radius)
            for candidate_index, candidate in enumerate(candidates):
                distance = hypot(candidate.x - predicted_x, candidate.y - predicted_y)
                if distance > track_gate:
                    continue
                size_ratio = max(candidate.radius, 1e-6) / max(track.last.radius, 1e-6)
                if not 0.35 <= size_ratio <= 2.8:
                    continue
                # Prefer spatially close candidates, but keep a strong penalty
                # for implausible size jumps so the tracker cannot jump from a
                # thin rim fragment to the ball.
                score = distance + abs(log(size_ratio)) * track_gate * 0.35
                pairs.append((score, track_index, candidate_index))

        pairs.sort(key=lambda item: item[0])
        matched_tracks: set[int] = set()
        matched_candidates: set[int] = set()
        for _score, track_index, candidate_index in pairs:
            if track_index in matched_tracks or candidate_index in matched_candidates:
                continue
            active[track_index].add(candidates[candidate_index])
            matched_tracks.add(track_index)
            matched_candidates.add(candidate_index)

        for candidate_index, candidate in enumerate(candidates):
            if candidate_index not in matched_candidates:
                active.append(BallTrack(points=[candidate]))

        expired = [track for track in active if track.misses > max_gap]
        if expired:
            finished.extend(expired)
            active = [track for track in active if track.misses <= max_gap]

    finished.extend(active)

    minimum_range = min_vertical_range_ratio * frame_height
    tracks = [
        track
        for track in finished
        if len(track.points) >= min_points
        and max(point.y for point in track.points) - min(point.y for point in track.points)
        >= minimum_range
    ]
    tracks.sort(key=lambda track: track.points[0].timestamp)
    return tracks
