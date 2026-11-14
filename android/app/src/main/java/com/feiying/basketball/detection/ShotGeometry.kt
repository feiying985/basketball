package com.feiying.basketball.detection

import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/** A hoop opening in analysis-frame coordinates. */
data class Hoop(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    val halfWidth: Float get() = width / 2f
    val halfHeight: Float get() = height / 2f
}

data class DetectedShot(
    val timestamp: Double,
    val frameIndex: Int,
    val made: Boolean,
)

/** Auto-detected hoop plus how clearly it beat the alternatives. */
data class HoopDetection(
    val hoop: Hoop,
    val confidence: Double,
)

private const val MIN_ARC_RATIO = 0.015
private const val APEX_ABOVE_RIM_RATIO = 0.01
private const val BELOW_RIM_RATIO = 0.01
private const val NEAR_RIM_WIDTH_FACTOR = 1.8f
private const val MAKE_WIDTH_FACTOR = 0.85f
private const val SEARCH_WINDOW = 12

/**
 * Geometry rules that turn a ball trajectory into make/miss events.
 *
 * Ported from `server/app/services/shot_geometry.py`. A shot is reported when
 * the tracked ball rises above the hoop, descends back towards it and passes
 * near the rim. A make crosses the rim plane inside the opening; otherwise it
 * is a near miss.
 */
fun detectShotEvents(
    tracks: List<BallTrack>,
    hoop: Hoop,
    frameHeight: Int,
    cooldownSeconds: Double = 1.5,
): List<DetectedShot> {
    val events = mutableListOf<DetectedShot>()
    tracks.forEach { track ->
        events.addAll(trackEvents(track, hoop, frameHeight))
    }
    return deduplicate(events, cooldownSeconds)
}

private fun trackEvents(track: BallTrack, hoop: Hoop, frameHeight: Int): List<DetectedShot> {
    val points = track.points
    if (points.size < 5) return emptyList()

    val ys = smooth(points.map { it.y.toDouble() }, window = 3)
    val minArc = max(MIN_ARC_RATIO * frameHeight, 2.0)
    val events = mutableListOf<DetectedShot>()

    for (index in 1 until points.size - 1) {
        if (!(ys[index] <= ys[index - 1] && ys[index] <= ys[index + 1])) continue
        val rimY = hoop.centerY.toDouble()
        // The ball must be at (or above) the rim at the apex to be a shot.
        if (ys[index] > rimY - APEX_ABOVE_RIM_RATIO * frameHeight) continue
        // A pass or a dribble has no meaningful rise/fall around the apex.
        val riseStart = max(0, index - SEARCH_WINDOW)
        if (index - riseStart < 2) continue
        if (ys.subList(riseStart, index).maxOrNull()!! - ys[index] < minArc) continue
        // A real shot rises from below the rim. Requiring a pre-apex point below
        // the rim prevents a rim bounce from being counted as a second shot.
        if (ys.subList(riseStart, index).maxOrNull()!! <
            rimY + BELOW_RIM_RATIO * frameHeight
        ) {
            continue
        }
        val fallEnd = min(points.size, index + SEARCH_WINDOW + 1)
        if (fallEnd - index < 2) continue
        if (ys.subList(index + 1, fallEnd).maxOrNull()!! - ys[index] < minArc) continue
        // Shots travel towards the hoop; ignore arcs far away from it.
        if ((points[index].x - hoop.centerX).absoluteValue > hoop.width * 4f) continue

        val event = crossingEvent(points, ys, index, fallEnd, hoop, frameHeight)
        if (event != null) events.add(event)
    }

    return events
}

private fun crossingEvent(
    points: List<BallPoint>,
    ys: List<Double>,
    apex: Int,
    fallEnd: Int,
    hoop: Hoop,
    frameHeight: Int,
): DetectedShot? {
    val rimY = hoop.centerY.toDouble()
    val minimumBelow = 0.01 * frameHeight

    var crossing: Int? = null
    for (index in apex + 1 until fallEnd) {
        if (ys[index] >= rimY) {
            crossing = index
            break
        }
    }

    if (crossing != null) {
        val previousY = ys[crossing - 1]
        val currentY = ys[crossing]
        val span = currentY - previousY
        var ratio = if (span <= 1e-6) 0.0 else (rimY - previousY) / span
        ratio = min(max(ratio, 0.0), 1.0)
        val xAtRim = points[crossing - 1].x +
            ratio * (points[crossing].x - points[crossing - 1].x)
        val distanceX = (xAtRim - hoop.centerX).absoluteValue

        val after = ys.subList(crossing, min(ys.size, crossing + 6))
        if (after.isEmpty() || after.maxOrNull()!! < rimY + minimumBelow) {
            // The track ends exactly at the rim; not enough evidence it went in.
            return null
        }
        val bouncedOut = after.minOrNull()!! < rimY - minimumBelow

        if (distanceX <= hoop.halfWidth * MAKE_WIDTH_FACTOR && !bouncedOut) {
            val point = points[crossing]
            return DetectedShot(point.timestamp, point.frame, made = true)
        }
        if (distanceX <= hoop.halfWidth * NEAR_RIM_WIDTH_FACTOR) {
            val point = points[crossing]
            return DetectedShot(point.timestamp, point.frame, made = false)
        }

        // The ball crossed the rim plane far outside the opening. If it touched
        // the rim before deflecting away, it is still a shot attempt.
        val closest = nearRimPoint(points, apex + 1, crossing, hoop, frameHeight)
        if (closest != null) {
            return DetectedShot(closest.timestamp, closest.frame, made = false)
        }
        return null
    }

    // No rim-plane crossing: the ball may have hit the rim and bounced away.
    val closest = nearRimPoint(points, apex + 1, fallEnd, hoop, frameHeight)
        ?: return null
    if (ys.subList(apex + 1, fallEnd).maxOrNull()!! < rimY + minimumBelow) return null
    return DetectedShot(closest.timestamp, closest.frame, made = false)
}

private fun nearRimPoint(
    points: List<BallPoint>,
    start: Int,
    end: Int,
    hoop: Hoop,
    frameHeight: Int,
): BallPoint? {
    val band = max(hoop.halfHeight.toDouble(), 0.03 * frameHeight)
    val candidates = (start until end).map { points[it] }.filter { point ->
        (point.y.toDouble() - hoop.centerY).absoluteValue <= band
    }
    if (candidates.isEmpty()) return null
    val closest = candidates.minBy { (it.x - hoop.centerX).absoluteValue }
    if ((closest.x - hoop.centerX).absoluteValue >
        hoop.halfWidth * NEAR_RIM_WIDTH_FACTOR
    ) {
        return null
    }
    return closest
}

private fun smooth(values: List<Double>, window: Int): List<Double> {
    if (window <= 1 || values.size < window) return values.map { it.toDouble() }
    val padding = window / 2
    val padded = mutableListOf<Double>()
    repeat(padding) { padded.add(values.first()) }
    padded.addAll(values)
    repeat(padding) { padded.add(values.last()) }
    return values.indices.map { index ->
        val chunk = padded.subList(index, index + window)
        val value = chunk.sum() / window
        if (value.isFinite()) value else values[index]
    }
}

private fun deduplicate(
    events: List<DetectedShot>,
    cooldownSeconds: Double,
): List<DetectedShot> {
    val ordered = events.sortedBy { it.timestamp }
    val deduplicated = mutableListOf<DetectedShot>()
    for (event in ordered) {
        val previous = deduplicated.lastOrNull()
        if (previous == null || event.timestamp - previous.timestamp > cooldownSeconds) {
            deduplicated.add(event)
            continue
        }
        if (event.made && !previous.made) {
            deduplicated[deduplicated.lastIndex] = event
        }
    }
    return deduplicated
}
