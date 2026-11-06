package com.feiying.basketball.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoopRoiTest {
    @Test
    fun `parse reads normalized region`() {
        val roi = HoopRoi.parse("0.465,0.305,0.055,0.05")

        assertEquals(0.465f, roi?.x)
        assertEquals(0.305f, roi?.y)
        assertEquals(0.055f, roi?.width)
        assertEquals(0.05f, roi?.height)
        assertTrue(roi!!.isValid)
    }

    @Test
    fun `parse rejects malformed and out-of-range values`() {
        assertNull(HoopRoi.parse(null))
        assertNull(HoopRoi.parse("0.1,0.2,0.3"))
        assertNull(HoopRoi.parse("a,b,c,d"))
        assertNull(HoopRoi.parse("0.5,0.5,0.6,0.1"))
        assertNull(HoopRoi.parse("0.1,0.1,0,0.2"))
    }

    @Test
    fun `form value uses five decimals and dot separator`() {
        val value = HoopRoi(0.4f, 0.2f, 0.1f, 0.05f).toFormValue()

        assertEquals("0.40000,0.20000,0.10000,0.05000", value)
    }

    @Test
    fun `validity rejects rectangles outside the frame`() {
        assertFalse(HoopRoi(0.9f, 0.1f, 0.2f, 0.1f).isValid)
        assertTrue(HoopRoi(0f, 0f, 1f, 1f).isValid)
    }
}
