package com.feiying.basketball.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the server's pure trajectory tests to the on-device implementation. */
class ShotGeometryTest {
    private val hoop = Hoop(centerX = 320f, centerY = 100f, width = 120f, height = 10f)
    private val frameHeight = 360

    @Test
    fun madeShot() {
        val (xs, ys) = arc(320f)
        val events = detectShotEvents(listOf(track(xs, ys)), hoop, frameHeight, 1.0)

        assertEquals(1, events.size)
        assertTrue(events[0].made)
    }

    @Test
    fun nearMiss() {
        val (_, ys) = arc(320f)
        val events = detectShotEvents(
            listOf(track(missXs(), ys)),
            hoop,
            frameHeight,
            1.0,
        )

        assertEquals(1, events.size)
        assertEquals(false, events[0].made)
    }

    @Test
    fun dribbleIsIgnored() {
        val ys = listOf(
            310f, 320f, 330f, 335f, 330f, 320f, 310f, 300f,
            310f, 320f, 330f, 335f, 330f, 320f, 310f, 300f,
        )
        val events = detectShotEvents(
            listOf(track(List(ys.size) { 320f }, ys)),
            hoop,
            frameHeight,
            1.0,
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun rimBounceIsAMiss() {
        val ys = listOf(
            300f, 280f, 240f, 190f, 140f, 100f, 80f, 65f, 58f, 55f, 55f, 58f,
            65f, 80f, 95f, 90f, 80f, 70f, 62f, 58f, 60f, 80f, 150f, 300f,
        )
        val xs = List(14) { 320f } + listOf(
            320f, 330f, 350f, 380f, 420f, 460f, 500f, 540f, 580f, 620f,
        )
        val events = detectShotEvents(listOf(track(xs, ys)), hoop, frameHeight, 1.0)

        assertEquals(1, events.size)
        assertEquals(false, events[0].made)
    }

    @Test
    fun duplicateTracksAreDeduplicatedAndMakeWins() {
        val (madeXs, madeYs) = arc(320f)
        val tracks = listOf(track(missXs(), madeYs), track(madeXs, madeYs))

        val events = detectShotEvents(tracks, hoop, frameHeight, 1.0)

        assertEquals(1, events.size)
        assertTrue(events[0].made)
    }

    @Test
    fun cooldownCollapsesTwoCloseShots() {
        val (xs, ys) = arc(320f)
        val first = track(xs, ys)
        val secondPoints = first.points.map { point ->
            point.copy(
                frame = point.frame + 3,
                timestamp = point.timestamp + 0.3,
            )
        }
        val events = detectShotEvents(
            listOf(first, BallTrack(secondPoints.toMutableList())),
            hoop,
            frameHeight,
            1.0,
        )

        assertEquals(1, events.size)
    }

    @Test
    fun wideArcIsNotAShot() {
        val xs = List(20) { 320f + 300f * (it / 19f) }
        val (_, ys) = arc(320f)
        val events = detectShotEvents(listOf(track(xs, ys)), hoop, frameHeight, 1.0)

        assertTrue(events.isEmpty())
    }

    private fun track(xs: List<Float>, ys: List<Float>, fps: Double = 10.0): BallTrack {
        require(xs.size == ys.size)
        return BallTrack(
            xs.indices.map { index ->
                BallPoint(
                    frame = index,
                    timestamp = index / fps,
                    x = xs[index],
                    y = ys[index],
                    radius = 12f,
                )
            }.toMutableList(),
        )
    }

    private fun arc(xApex: Float, samples: Int = 20): Pair<List<Float>, List<Float>> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (index in 0 until samples) {
            val u = index.toDouble() / (samples - 1)
            val t = 2 * u - 1
            ys.add((55 + (300 - 55) * t * t).toFloat())
            xs.add(xApex)
        }
        return xs to ys
    }

    private fun missXs(apex: Float = 390f, samples: Int = 20): List<Float> =
        List(samples) { index ->
            val t = 1 - index / (samples - 1).toDouble()
            (apex + (320 - apex) * t * t).toFloat()
        }
}
