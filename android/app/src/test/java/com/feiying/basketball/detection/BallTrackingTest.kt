package com.feiying.basketball.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the server's candidate association tests. */
class BallTrackingTest {

    @Test
    fun associatesMovingBallIntoOneTrack() {
        val detections = (0 until 10).map { frame ->
            listOf(point(frame, 320f, 300f - frame * 10f))
        }

        val tracks = buildTracks(
            detections,
            frameWidth = 640,
            frameHeight = 360,
            minPoints = 5,
            minVerticalRangeRatio = 0.04,
        )

        assertEquals(1, tracks.size)
        assertEquals(10, tracks[0].points.size)
    }

    @Test
    fun bridgesShortGap() {
        val detections = (0 until 10).map { frame ->
            if (frame == 4) emptyList() else listOf(point(frame, 320f, 300f - frame * 10f))
        }

        val tracks = buildTracks(
            detections,
            frameWidth = 640,
            frameHeight = 360,
            maxGap = 2,
            minPoints = 5,
            minVerticalRangeRatio = 0.04,
        )

        assertEquals(1, tracks.size)
        assertEquals(9, tracks[0].points.size)
    }

    @Test
    fun staticOrangeObjectIsFilteredOut() {
        val detections = (0 until 10).map { frame ->
            listOf(point(frame, 320f, 100f, radius = 4f))
        }

        val tracks = buildTracks(
            detections,
            frameWidth = 640,
            frameHeight = 360,
            minPoints = 5,
            minVerticalRangeRatio = 0.04,
        )

        assertTrue(tracks.isEmpty())
    }

    private fun point(
        frame: Int,
        x: Float,
        y: Float,
        radius: Float = 12f,
    ): BallPoint = BallPoint(
        frame = frame,
        timestamp = frame / 10.0,
        x = x,
        y = y,
        radius = radius,
    )
}
