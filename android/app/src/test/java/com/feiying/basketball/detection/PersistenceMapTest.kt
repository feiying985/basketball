package com.feiying.basketball.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistenceMapTest {

    @Test
    fun firstFrameIsSeededWithFullValues() {
        val map = PersistenceMap(width = 3, height = 1, alpha = 0.5f)

        map.update(byteArrayOf(1, 0, 0))

        assertEquals(1f, map.values[0], 0.001f)
        assertEquals(0f, map.values[1], 0.001f)
        assertEquals(0f, map.values[2], 0.001f)
    }

    @Test
    fun staticPixelsStayHighAndMovingPixelsDecay() {
        val map = PersistenceMap(width = 3, height = 1, alpha = 0.5f)
        map.update(byteArrayOf(1, 0, 0))

        map.update(byteArrayOf(0, 1, 0))
        assertEquals(0.5f, map.values[0], 0.001f)
        assertEquals(0.5f, map.values[1], 0.001f)

        map.update(byteArrayOf(0, 1, 0))
        assertEquals(0.25f, map.values[0], 0.001f)
        assertEquals(0.75f, map.values[1], 0.001f)
    }
}
