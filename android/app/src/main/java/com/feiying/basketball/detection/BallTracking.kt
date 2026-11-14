package com.feiying.basketball.detection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/** A candidate ball centre in analysis-frame coordinates. */
data class BallPoint(
    val frame: Int,
    val timestamp: Double,
    val x: Float,
    val y: Float,
    val radius: Float,
)

/** A sequence of associated ball candidates. */
class BallTrack(points: MutableList<BallPoint> = mutableListOf()) {
    val points: MutableList<BallPoint> = points
    var misses: Int = 0

    val last: BallPoint
        get() = points.last()

    /** Per-sample-frame velocity estimate. */
    fun velocity(): Pair<Float, Float> {
        if (points.size < 2) return 0f to 0f
        val previous = points[points.size - 2]
        val latest = points.last()
        val delta = maxOf(latest.frame - previous.frame, 1)
        return (latest.x - previous.x) / delta to (latest.y - previous.y) / delta
    }

    fun predict(frame: Int): Pair<Float, Float> {
        val (vx, vy) = velocity()
        val steps = maxOf(frame - last.frame, 0)
        return last.x + vx * steps to last.y + vy * steps
    }

    fun add(point: BallPoint) {
        points.add(point)
        misses = 0
    }
}

private data class Match(val score: Double, val trackIndex: Int, val candidateIndex: Int)

/**
 * Associate per-frame candidates into tracks and keep shot-like ones.
 *
 * This is a direct port of `server/app/services/ball_tracking.py`: greedy
 * nearest-neighbour association with a constant-velocity prediction, a size
 * gate to avoid jumping between the ball and rim fragments, and a minimum
 * vertical range so static orange objects are discarded.
 */
fun buildTracks(
    detections: List<List<BallPoint>>,
    frameWidth: Int,
    frameHeight: Int,
    maxGap: Int = 5,
    minPoints: Int = 5,
    minVerticalRangeRatio: Double = 0.04,
): List<BallTrack> {
    var active: MutableList<BallTrack> = mutableListOf()
    val finished: MutableList<BallTrack> = mutableListOf()
    val gate = maxOf(0.12 * frameWidth, 24.0)

    detections.forEachIndexed { frame, candidates ->
        active.forEach { it.misses += 1 }

        val pairs = mutableListOf<Match>()
        active.forEachIndexed { trackIndex, track ->
            val (predictedX, predictedY) = track.predict(frame)
            val trackGate = maxOf(gate, 3.5 * track.last.radius)
            candidates.forEachIndexed { candidateIndex, candidate ->
                val distance = hypot(
                    (candidate.x - predictedX).toDouble(),
                    (candidate.y - predictedY).toDouble(),
                )
                if (distance > trackGate) return@forEachIndexed
                val sizeRatio =
                    candidate.radius.toDouble() / maxOf(track.last.radius.toDouble(), 1e-6)
                if (sizeRatio < 0.35 || sizeRatio > 2.8) return@forEachIndexed
                val score = distance + abs(ln(sizeRatio)) * trackGate * 0.35
                pairs.add(Match(score, trackIndex, candidateIndex))
            }
        }

        pairs.sortBy { it.score }
        val matchedTracks = mutableSetOf<Int>()
        val matchedCandidates = mutableSetOf<Int>()
        for (match in pairs) {
            if (match.trackIndex in matchedTracks || match.candidateIndex in matchedCandidates) {
                continue
            }
            active[match.trackIndex].add(candidates[match.candidateIndex])
            matchedTracks.add(match.trackIndex)
            matchedCandidates.add(match.candidateIndex)
        }

        candidates.forEachIndexed { candidateIndex, candidate ->
            if (candidateIndex !in matchedCandidates) {
                active.add(BallTrack(mutableListOf(candidate)))
            }
        }

        val expired = active.filter { it.misses > maxGap }
        if (expired.isNotEmpty()) {
            finished.addAll(expired)
            active = active.filter { it.misses <= maxGap }.toMutableList()
        }
    }

    finished.addAll(active)

    val minimumRange = minVerticalRangeRatio * frameHeight
    return finished
        .filter { track ->
            track.points.size >= minPoints &&
                (track.points.maxOf { it.y } - track.points.minOf { it.y }) >= minimumRange
        }
        .sortedBy { it.points.first().timestamp }
}
