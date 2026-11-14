package com.feiying.basketball.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class VisionTest {

    @Test
    fun orangeMaskKeepsOrangeAndRejectsBlueAndGray() {
        val width = 3
        val pixels = intArrayOf(
            0xFFFF8C00.toInt(), // orange
            0xFF0000FF.toInt(), // blue
            0xFF646464.toInt(), // gray
        )

        val mask = Vision.orangeMask(pixels, width, 1, DetectionConfig())

        assertEquals(1, mask[0].toInt())
        assertEquals(0, mask[1].toInt())
        assertEquals(0, mask[2].toInt())
    }

    @Test
    fun distanceTransformReturnsEuclideanDistance() {
        val width = 5
        val height = 5
        val mask = ByteArray(width * height) { 1 }
        mask[2 * width + 2] = 0

        val distance = Vision.distanceTransform(mask, width, height)

        assertEquals(sqrt(8.0), distance[0].toDouble(), 0.01)
        assertEquals(0.0, distance[2 * width + 2].toDouble(), 0.01)
    }

    @Test
    fun ballCandidatesFindDiskCentre() {
        val width = 200
        val height = 200
        val mask = diskMask(width, height, centerX = 100, centerY = 100, radius = 12)
        val persistent = FloatArray(width * height)

        val candidates = Vision.ballCandidates(
            mask = mask,
            persistent = persistent,
            width = width,
            height = height,
            frame = 0,
            timestamp = 0.0,
            config = DetectionConfig(),
        )

        assertEquals(1, candidates.size)
        assertTrue(abs(candidates[0].x - 100f) <= 1f)
        assertTrue(abs(candidates[0].y - 100f) <= 1f)
        assertEquals(12f, candidates[0].radius, 1.5f)
    }

    @Test
    fun persistentStaticOrangeObjectIsRejected() {
        val width = 200
        val height = 200
        val mask = diskMask(width, height, centerX = 100, centerY = 100, radius = 12)
        val persistent = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = x - 100
            val dy = y - 100
            if (dx * dx + dy * dy <= 12 * 12) 1f else 0f
        }

        val candidates = Vision.ballCandidates(
            mask = mask,
            persistent = persistent,
            width = width,
            height = height,
            frame = 0,
            timestamp = 0.0,
            config = DetectionConfig(),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun findsPersistentHoopRectangle() {
        val width = 320
        val height = 180
        val persistent = FloatArray(width * height)
        for (y in 50..58) {
            for (x in 140..180) {
                persistent[y * width + x] = 1f
            }
        }

        val detection = Vision.findHoop(persistent, width, height, DetectionConfig())

        assertNotNull(detection)
        assertTrue(abs(detection!!.hoop.centerX - 160f) <= 4f)
        assertTrue(abs(detection.hoop.centerY - 54f) <= 4f)
        assertTrue(detection.hoop.width in 35f..48f)
    }

    @Test
    fun cleanMaskRemovesIsolatedNoise() {
        val width = 20
        val height = 20
        val mask = ByteArray(width * height)
        mask[10 * width + 10] = 1

        val cleaned = Vision.cleanMask(mask, width, height)

        assertTrue(cleaned.all { it.toInt() == 0 })
    }

    private fun diskMask(
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): ByteArray {
        val mask = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radius * radius) {
                    mask[y * width + x] = 1
                }
            }
        }
        return mask
    }
}
