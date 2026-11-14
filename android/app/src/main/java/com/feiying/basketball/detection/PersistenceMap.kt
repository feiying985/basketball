package com.feiying.basketball.detection

/**
 * Exponential moving average of the orange mask, seeded with the first frame.
 *
 * Static orange objects (rims, lamp posts, posters) reach values near 1 while a
 * moving ball stays low except while it sits at the top of its arc. The server
 * uses the same `cv2.accumulateWeighted` recurrence with alpha 0.12.
 */
class PersistenceMap(
    width: Int,
    height: Int,
    private val alpha: Float = 0.12f,
) {
    val values = FloatArray(width * height)
    private var seeded = false

    fun update(mask: ByteArray) {
        require(mask.size >= values.size) { "mask is smaller than the frame" }
        val keep = 1f - alpha
        if (!seeded) {
            for (index in values.indices) {
                values[index] = if (mask[index].toInt() != 0) 1f else 0f
            }
            seeded = true
            return
        }
        for (index in values.indices) {
            val current = if (mask[index].toInt() != 0) 1f else 0f
            values[index] = keep * values[index] + alpha * current
        }
    }
}
