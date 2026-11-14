package com.feiying.basketball.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end synthetic check of the pure detection pieces: orange mask slices
 * are fed through persistence, candidate detection, tracking and geometry.
 */
class DetectionPipelineTest {

    private val width = 320
    private val height = 180
    private val hoopLeft = 140
    private val hoopRight = 180
    private val hoopTop = 95
    private val hoopBottom = 103
    private val ballRadius = 8
    private val sampleFps = 10.0
    private val frames = 20

    @Test
    fun movingBallArcIsDetectedAndStaticHoopIsFound() {
        val persistent = PersistenceMap(width, height, alpha = 0.12f)
        val detections = mutableListOf<List<BallPoint>>()

        for (index in 0 until frames) {
            val u = index / (frames - 1).toDouble()
            val ballY = (150 - 90 * sin(PI * u)).toInt()
            val mask = frameMask(ballY)
            persistent.update(mask)
            detections.add(
                Vision.ballCandidates(
                    mask = mask,
                    persistent = persistent.values,
                    width = width,
                    height = height,
                    frame = index,
                    timestamp = index / sampleFps,
                    config = DetectionConfig(),
                ),
            )
        }

        val hoop = Vision.findHoop(persistent.values, width, height, DetectionConfig())
        assertNotNull(hoop)
        assertTrue(hoop!!.hoop.centerX in 155f..165f)

        val tracks = buildTracks(
            detections = detections,
            frameWidth = width,
            frameHeight = height,
            maxGap = 5,
            minPoints = 5,
            minVerticalRangeRatio = 0.02,
        )
        assertTrue(tracks.isNotEmpty())

        val manualHoop = Hoop(
            centerX = (hoopLeft + hoopRight) / 2f,
            centerY = (hoopTop + hoopBottom) / 2f,
            width = (hoopRight - hoopLeft).toFloat(),
            height = (hoopBottom - hoopTop).toFloat(),
        )
        val events = detectShotEvents(
            tracks = tracks,
            hoop = manualHoop,
            frameHeight = height,
            cooldownSeconds = 1.0,
        )
        assertEquals(1, events.size)
        assertTrue(events[0].made)
    }

    private fun frameMask(ballY: Int): ByteArray {
        val mask = ByteArray(width * height)
        for (y in hoopTop..hoopBottom) {
            for (x in hoopLeft..hoopRight) {
                mask[y * width + x] = 1
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - 160
                val dy = y - ballY
                if (dx * dx + dy * dy <= ballRadius * ballRadius) {
                    mask[y * width + x] = 1
                }
            }
        }
        return mask
    }
}
