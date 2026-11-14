package com.feiying.basketball.detection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin image primitives for the on-device detector.
 *
 * They mirror the OpenCV calls in
 * `server/app/services/classical_shot_detection.py` closely enough to reuse
 * the same tracking and geometry rules, without pulling a native CV library
 * (and its per-ABI APK size) into the app. Frames are represented as ARGB
 * pixel arrays and orange masks as 0/1 bytes, so the whole pipeline is unit
 * testable on the JVM.
 */
object Vision {
    private const val FAR = 1e9f

    /**
     * Build an orange mask with the same HSV bounds as the server.
     * OpenCV's hue is 0..179 while RGB hue is 0..360, so it is halved here.
     */
    fun orangeMask(
        argb: IntArray,
        width: Int,
        height: Int,
        config: DetectionConfig,
    ): ByteArray {
        require(argb.size >= width * height) { "pixel buffer is smaller than the frame" }
        val mask = ByteArray(width * height)
        for (index in 0 until width * height) {
            val pixel = argb[index]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val maximum = maxOf(r, g, b)
            val minimum = minOf(r, g, b)
            val delta = maximum - minimum
            val value = maximum
            val saturation = if (maximum == 0) 0 else 255 * delta / maximum
            var hue = 0
            if (delta != 0) {
                hue = when (maximum) {
                    r -> 60 * (g - b) / delta
                    g -> 60 * (b - r) / delta + 120
                    else -> 60 * (r - g) / delta + 240
                }
                if (hue < 0) hue += 360
            }
            val openCvHue = hue / 2
            if (openCvHue in config.hueMin..config.hueMax &&
                saturation >= config.saturationMin &&
                value >= config.valueMin
            ) {
                mask[index] = 1
            }
        }
        return mask
    }

    /** Equivalent of close(5x5 ellipse) followed by open(3x3 ellipse). */
    fun cleanMask(mask: ByteArray, width: Int, height: Int): ByteArray {
        val closed = erode(dilate(mask, width, height, 5, 5), width, height, 5, 5)
        return dilate(erode(closed, width, height, 3, 3), width, height, 3, 3)
    }

    /**
     * Euclidean distance from every pixel to the nearest background pixel,
     * matching `cv2.distanceTransform(mask)`: the distance is 0 outside the
     * mask and grows towards the centre of a filled blob.
     */
    fun distanceTransform(mask: ByteArray, width: Int, height: Int): FloatArray {
        val distance = FloatArray(width * height) {
            if (mask[it].toInt() == 0) 0f else FAR
        }
        val maxSide = max(width, height)
        val source = FloatArray(maxSide)
        val result = FloatArray(maxSide)
        val v = IntArray(maxSide)
        val z = FloatArray(maxSide + 1)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) source[x] = distance[row + x]
            edt1d(source, width, result, v, z)
            for (x in 0 until width) distance[row + x] = result[x]
        }
        for (x in 0 until width) {
            for (y in 0 until height) source[y] = distance[y * width + x]
            edt1d(source, height, result, v, z)
            for (y in 0 until height) distance[y * width + x] = result[y]
        }
        for (index in distance.indices) distance[index] = sqrt(distance[index])
        return distance
    }

    /**
     * Find round orange blobs using distance-transform maxima.
     *
     * A plain contour scan merges the ball and the rim while they touch; peaks
     * of the distance transform keep the ball visible exactly when it matters.
     * Static orange structures (lamp posts, posters) are rejected through the
     * persistence map before the candidate slots are filled.
     */
    fun ballCandidates(
        mask: ByteArray,
        persistent: FloatArray,
        width: Int,
        height: Int,
        frame: Int,
        timestamp: Double,
        config: DetectionConfig,
    ): List<BallPoint> {
        val minimumRadius = max(config.ballMinRadiusRatio * min(width, height), 2.0)
        val maximumRadius = config.ballMaxRadiusRatio * min(width, height)
        val distance = distanceTransform(mask, width, height)

        val peaks = mutableListOf<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = distance[index]
                if (value < minimumRadius || value > maximumRadius) continue
                var isMaximum = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        if (distance[ny * width + nx] > value + 1e-3f) {
                            isMaximum = false
                            break
                        }
                    }
                    if (!isMaximum) break
                }
                if (isMaximum) peaks.add(index)
            }
        }
        if (peaks.isEmpty()) return emptyList()

        // A summed-area table keeps the persistence probe O(1) per peak even
        // when a large orange area produces many plateau maxima.
        val integral = summedAreaTable(persistent, width, height)
        val ordered = peaks
            .filter { index ->
                meanAround(
                    integral = integral,
                    width = width,
                    height = height,
                    x = index % width,
                    y = index / width,
                    radius = distance[index],
                ) < config.staticPersistenceThreshold
            }
            .sortedByDescending { distance[it] }

        // Non-maximum suppression with the minimum-ball radius mirrors the
        // dilation window OpenCV uses to collapse plateaus of a peak.
        val selected = mutableListOf<Int>()
        for (index in ordered) {
            val x = index % width
            val y = index / width
            val tooClose = selected.any { picked ->
                val px = picked % width
                val py = picked / width
                hypot((x - px).toDouble(), (y - py).toDouble()) <= minimumRadius
            }
            if (!tooClose) selected.add(index)
        }

        return selected
            .map { index ->
                val x = index % width
                val y = index / width
                BallPoint(
                    frame = frame,
                    timestamp = timestamp,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    radius = distance[index],
                )
            }
            .sortedByDescending { it.radius }
            .take(config.maxBallCandidates)
    }

    /**
     * Find the persistent orange rectangle that most looks like a rim.
     *
     * Ported from `_find_hoop`; confidence is the relative margin over the
     * runner-up so the caller can ask for a manual ROI when ambiguous.
     */
    fun findHoop(
        persistent: FloatArray,
        width: Int,
        height: Int,
        config: DetectionConfig,
    ): HoopDetection? {
        val mask = ByteArray(width * height) {
            if (persistent[it] >= config.hoopPersistence) 1 else 0
        }
        val closed = close(mask, width, height, 7, 3)
        val components = connectedComponents(closed, width, height)
        if (components.isEmpty()) return null

        data class Scored(val score: Double, val component: Component)

        val scored = mutableListOf<Scored>()
        for (component in components) {
            val area = component.area
            if (area < 0.0004 * width * height) continue
            val candidateWidth = component.width
            val candidateHeight = component.height
            if (candidateWidth < config.hoopMinWidthRatio * width) continue
            if (candidateWidth > config.hoopMaxWidthRatio * width) continue
            if (candidateHeight > config.hoopMaxHeightRatio * candidateWidth) continue
            val centerY = component.y0 + candidateHeight / 2.0
            if (centerY > config.hoopTopRatio * height) continue
            val persistence = meanPersistence(
                persistent,
                component,
                width,
            )
            val widthScore = min(candidateWidth / (0.15 * width), 1.5)
            val positionScore = 1.0 - 0.5 * abs(centerY / height - 0.3)
            val centerX = component.x0 + candidateWidth / 2.0
            val centrality = 1.0 - min(abs(centerX / width - 0.5) * 2.0, 1.0)
            val centerFactor = (1.0 - config.hoopCenterWeight) +
                config.hoopCenterWeight * centrality
            val score = persistence * (0.5 + widthScore) * (0.5 + positionScore) * centerFactor
            scored.add(Scored(score, component))
        }
        if (scored.isEmpty()) return null

        scored.sortByDescending { it.score }
        val best = scored.first()
        val confidence = if (scored.size > 1 && best.score > 0.0) {
            ((best.score - scored[1].score) / best.score).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        return HoopDetection(
            hoop = Hoop(
                centerX = (best.component.x0 + best.component.width / 2.0).toFloat(),
                centerY = (best.component.y0 + best.component.height / 2.0).toFloat(),
                width = best.component.width.toFloat(),
                height = max(best.component.height, 2).toFloat(),
            ),
            confidence = confidence,
        )
    }

    /** Summed-area table with a one-pixel zero border, indexed as (y * (w+1) + x). */
    private fun summedAreaTable(
        values: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val stride = width + 1
        val table = FloatArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0f
            for (x in 0 until width) {
                rowSum += values[y * width + x]
                table[(y + 1) * stride + (x + 1)] =
                    table[y * stride + (x + 1)] + rowSum
            }
        }
        return table
    }

    /**
     * Mean persistence inside a disk around a candidate, matching the server's
     * disk probe. Rows of the disk are summed from the summed-area table, so
     * the cost is O(radius) instead of O(radius^2).
     */
    private fun meanAround(
        integral: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Float,
    ): Double {
        val probe = max(radius.roundToInt(), 2)
        val stride = width + 1
        var sum = 0f
        var count = 0
        for (dy in -probe..probe) {
            val row = y + dy
            if (row !in 0 until height) continue
            val half = sqrt((probe * probe - dy * dy).toDouble()).toInt()
            val x0 = max(0, x - half)
            val x1 = min(width, x + half + 1)
            if (x0 >= x1) continue
            sum += integral[(row + 1) * stride + x1] -
                integral[row * stride + x1] -
                integral[(row + 1) * stride + x0] +
                integral[row * stride + x0]
            count += x1 - x0
        }
        return if (count == 0) 1.0 else sum.toDouble() / count
    }

    private fun meanPersistence(
        persistent: FloatArray,
        component: Component,
        width: Int,
    ): Double {
        var sum = 0.0
        var count = 0
        for (y in component.y0..component.y1) {
            for (x in component.x0..component.x1) {
                sum += persistent[y * width + x].toDouble()
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun close(mask: ByteArray, width: Int, height: Int, kernelW: Int, kernelH: Int): ByteArray {
        return erode(dilate(mask, width, height, kernelW, kernelH), width, height, kernelW, kernelH)
    }

    private fun dilate(
        mask: ByteArray,
        width: Int,
        height: Int,
        kernelW: Int,
        kernelH: Int,
    ): ByteArray = morphology(mask, width, height, kernelW, kernelH, dilate = true)

    private fun erode(
        mask: ByteArray,
        width: Int,
        height: Int,
        kernelW: Int,
        kernelH: Int,
    ): ByteArray = morphology(mask, width, height, kernelW, kernelH, dilate = false)

    /**
     * Separable rectangular morphology. The server uses elliptical kernels;
     * rectangles are a close, much cheaper approximation for noise cleanup.
     */
    private fun morphology(
        mask: ByteArray,
        width: Int,
        height: Int,
        kernelW: Int,
        kernelH: Int,
        dilate: Boolean,
    ): ByteArray {
        val radiusX = kernelW / 2
        val radiusY = kernelH / 2
        val horizontal = ByteArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = if (dilate) 0 else 1
                for (dx in -radiusX..radiusX) {
                    val sample = mask[row + (x + dx).coerceIn(0, width - 1)].toInt()
                    value = if (dilate) max(value, sample) else min(value, sample)
                }
                horizontal[row + x] = value.toByte()
            }
        }
        val output = ByteArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var value = if (dilate) 0 else 1
                for (dy in -radiusY..radiusY) {
                    val sample = horizontal[(y + dy).coerceIn(0, height - 1) * width + x].toInt()
                    value = if (dilate) max(value, sample) else min(value, sample)
                }
                output[y * width + x] = value.toByte()
            }
        }
        return output
    }

    private data class Component(
        val area: Int,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
    ) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    private fun connectedComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): List<Component> {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val components = mutableListOf<Component>()
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var area = 0
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val next = ny * width + nx
                        if (mask[next].toInt() != 0 && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }
            components.add(Component(area, minX, minY, maxX, maxY))
        }
        return components
    }

    /** Felzenszwalb-Huttenlocher 1D squared distance transform. */
    private fun edt1d(
        source: FloatArray,
        length: Int,
        output: FloatArray,
        v: IntArray,
        z: FloatArray,
    ) {
        var k = 0
        v[0] = 0
        z[0] = -FAR
        z[1] = FAR
        for (q in 1 until length) {
            var s = ((source[q] + q * q) - (source[v[k]] + v[k] * v[k])) /
                (2 * q - 2 * v[k])
            while (s <= z[k]) {
                k--
                s = ((source[q] + q * q) - (source[v[k]] + v[k] * v[k])) /
                    (2 * q - 2 * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = FAR
        }
        k = 0
        for (q in 0 until length) {
            while (z[k + 1] < q) k++
            val delta = q - v[k]
            output[q] = delta * delta + source[v[k]]
        }
    }
}
