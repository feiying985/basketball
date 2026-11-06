package com.feiying.basketball.data

import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.ClipStatus
import com.feiying.basketball.domain.ShotClip
import com.feiying.basketball.domain.ShotEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses queued job response`() {
        val dto = json.decodeFromString<AnalysisJobDto>(
            """
            {
              "id": "job-1",
              "status": "queued",
              "filename": "clip.mp4",
              "created_at": "2026-11-05T00:00:00Z",
              "result": null,
              "error": null
            }
            """.trimIndent(),
        )
        assertEquals("job-1", dto.id)
        assertEquals("queued", dto.status)
        assertEquals("clip.mp4", dto.filename)
        assertNull(dto.result)
    }

    @Test
    fun `parses completed job with snake case shot fields`() {
        val dto = json.decodeFromString<AnalysisJobDto>(
            """
            {
              "id": "job-2",
              "status": "completed",
              "filename": "clip.mp4",
              "created_at": "2026-11-05T00:00:00Z",
              "result": {
                "detector": "stub",
                "shots": [
                  {"timestamp_seconds": 4.5, "frame_index": 90, "made": true},
                  {"timestamp_seconds": 12.0, "frame_index": 240, "made": false}
                ],
                "note": "未检测到篮筐"
              }
            }
            """.trimIndent(),
        )
        val result = dto.result
        assertEquals("stub", result?.detector)
        assertEquals(2, result?.shots?.size)
        assertEquals(4.5, result?.shots?.get(0)?.timestampSeconds ?: 0.0, 0.001)
        assertEquals(90, result?.shots?.get(0)?.frameIndex)
        assertTrue(result?.shots?.get(0)?.made == true)
        assertEquals("未检测到篮筐", result?.note)
    }

    @Test
    fun `parses health response and ignores unknown fields`() {
        val dto = json.decodeFromString<HealthDto>(
            """
            {"status":"ok","service":"basketball","version":"0.1.0","detector":"stub","extra":1}
            """.trimIndent(),
        )
        assertEquals("0.1.0", dto.version)
        assertEquals("stub", dto.detector)
    }

    @Test
    fun `local result snapshot round trips`() {
        val result = AnalysisResult(
            detector = "stub",
            shots = listOf(ShotEvent(4.5, 90, true), ShotEvent(12.0, 240, false)),
            note = "test",
        )
        val encoded = json.encodeToString(AnalysisResult.serializer(), result)
        val decoded = json.decodeFromString(AnalysisResult.serializer(), encoded)
        assertEquals(result, decoded)
    }

    @Test
    fun `parses job stage clip metadata and clip note`() {
        val dto = json.decodeFromString<AnalysisJobDto>(
            """
            {
              "id": "job-3",
              "status": "processing",
              "stage": "clipping",
              "filename": "clip.mp4",
              "created_at": "2026-11-05T00:00:00Z",
              "result": {
                "detector": "classical",
                "shots": [
                  {
                    "timestamp_seconds": 4.5,
                    "frame_index": 90,
                    "made": true,
                    "clip": {
                      "status": "ready",
                      "start_seconds": 0.0,
                      "end_seconds": 9.5,
                      "duration_seconds": 9.5,
                      "size_bytes": 2400000,
                      "url": "/api/v1/analyses/job-3/clips/0",
                      "error": null
                    }
                  }
                ],
                "clip_note": "ok"
              }
            }
            """.trimIndent(),
        )
        assertEquals("clipping", dto.stage)
        assertEquals("ok", dto.result?.clipNote)
        val clip = dto.result?.shots?.get(0)?.clip
        assertEquals("ready", clip?.status)
        assertEquals(0.0, clip?.startSeconds ?: -1.0, 0.001)
        assertEquals(9.5, clip?.durationSeconds ?: -1.0, 0.001)
        assertEquals("/api/v1/analyses/job-3/clips/0", clip?.url)
    }

    @Test
    fun `old job json without clip fields still decodes`() {
        val dto = json.decodeFromString<AnalysisJobDto>(
            """
            {
              "id": "job-old",
              "status": "completed",
              "filename": "clip.mp4",
              "created_at": "2026-11-05T00:00:00Z",
              "result": {
                "detector": "stub",
                "shots": [{"timestamp_seconds": 4.5, "frame_index": 90, "made": true}]
              }
            }
            """.trimIndent(),
        )
        assertNull(dto.stage)
        assertNull(dto.result?.clipNote)
        assertNull(dto.result?.shots?.get(0)?.clip)
    }

    @Test
    fun `ready clip indexes skip misses and failed clips`() {
        val ready = ShotClip(
            status = ClipStatus.READY,
            startSeconds = 0.0,
            endSeconds = 9.0,
            durationSeconds = 9.0,
            url = "/clip/0",
        )
        val failed = ShotClip(
            status = ClipStatus.FAILED,
            startSeconds = 0.0,
            endSeconds = 9.0,
            durationSeconds = 9.0,
            error = "boom",
        )
        val result = AnalysisResult(
            detector = "stub",
            shots = listOf(
                ShotEvent(1.0, 1, true, ready),
                ShotEvent(2.0, 2, false, ready),
                ShotEvent(3.0, 3, true, failed),
                ShotEvent(4.0, 4, true),
            ),
        )

        assertEquals(listOf(0), result.readyClipIndexes)
        assertTrue(result.hasClipAttempt)
    }

    @Test
    fun `local snapshot with clips round trips`() {
        val result = AnalysisResult(
            detector = "stub",
            shots = listOf(
                ShotEvent(
                    4.5,
                    90,
                    true,
                    ShotClip(
                        status = ClipStatus.READY,
                        startSeconds = 0.0,
                        endSeconds = 9.5,
                        durationSeconds = 9.5,
                        sizeBytes = 1024,
                        url = "/clip/0",
                    ),
                ),
            ),
            clipNote = "服务端未安装 ffmpeg",
        )

        val encoded = json.encodeToString(AnalysisResult.serializer(), result)
        val decoded = json.decodeFromString(AnalysisResult.serializer(), encoded)

        assertEquals(result, decoded)
    }
}
