package com.feiying.basketball.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRulesTest {
    @Test
    fun `common video extensions are accepted case insensitively`() {
        listOf("clip.mp4", "CLIP.MOV", "a.m4v", "b.webm", "c.avi", "d.mkv")
            .forEach { assertTrue(it, VideoRules.isAllowedExtension(it)) }
    }

    @Test
    fun `unknown extension is rejected`() {
        assertFalse(VideoRules.isAllowedExtension("clip.exe"))
        assertFalse(VideoRules.isAllowedExtension("clip"))
        assertFalse(VideoRules.isAllowedExtension("clip.mp3"))
    }

    @Test
    fun `blocks unsupported format and oversized files`() {
        val validation = VideoRules.validate(
            filename = "clip.mov",
            sizeBytes = VideoRules.MAX_UPLOAD_BYTES + 1,
            durationMs = 10_000,
            readable = true,
        )
        assertEquals(listOf(VideoIssue.TOO_LARGE), validation.issues)
        assertFalse(validation.canAnalyze)
    }

    @Test
    fun `allows exactly the maximum upload size`() {
        val validation = VideoRules.validate(
            filename = "clip.mp4",
            sizeBytes = VideoRules.MAX_UPLOAD_BYTES,
            durationMs = 10_000,
            readable = true,
        )
        assertTrue(validation.canAnalyze)
    }

    @Test
    fun `long video only warns and does not block`() {
        val validation = VideoRules.validate(
            filename = "clip.mp4",
            sizeBytes = 1024,
            durationMs = VideoRules.LONG_VIDEO_WARNING_MS + 1,
            readable = true,
        )
        assertTrue(validation.canAnalyze)
        assertTrue(validation.hasLongWarning)
    }

    @Test
    fun `unreadable video blocks analysis`() {
        val validation = VideoRules.validate(
            filename = "clip.mp4",
            sizeBytes = 1024,
            durationMs = null,
            readable = false,
        )
        assertFalse(validation.canAnalyze)
        assertTrue(VideoIssue.UNREADABLE in validation.issues)
    }

    @Test
    fun `sanitize keeps safe names and extension`() {
        assertEquals("clip_1.mp4", VideoRules.sanitizeFilename("clip 1.mp4", "mp4"))
        assertEquals("投篮_01.mov", VideoRules.sanitizeFilename("投篮 01.mov", "mov"))
    }

    @Test
    fun `sanitize removes path separators and adds missing extension`() {
        assertEquals("evil.mp4", VideoRules.sanitizeFilename("../../evil", "mp4"))
        assertEquals("video.mp4", VideoRules.sanitizeFilename("", "mp4"))
    }

    @Test
    fun `mime types map to extensions`() {
        assertEquals("mov", VideoRules.extensionForMimeType("video/quicktime"))
        assertEquals("webm", VideoRules.extensionForMimeType("video/webm"))
        assertEquals("mp4", VideoRules.extensionForMimeType("video/mp4"))
        assertEquals("mp4", VideoRules.extensionForMimeType(null))
    }

    @Test
    fun `cache file names use a lower case extension`() {
        assertEquals("rec_123.mp4", VideoRules.buildCacheFilename("rec", "MP4", 123))
    }
}
