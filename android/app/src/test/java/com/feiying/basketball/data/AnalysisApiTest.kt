package com.feiying.basketball.data

import java.io.File
import com.feiying.basketball.domain.HoopRoi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnalysisApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `upload posts multipart file and reports progress`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {"id":"job-1","status":"queued","filename":"clip.mp4",
                     "created_at":"2026-11-05T00:00:00Z","result":null,"error":null}
                    """.trimIndent(),
                ),
        )
        val file = File.createTempFile("clip", ".mp4").apply {
            writeBytes(ByteArray(100 * 1024) { it.toByte() })
            deleteOnExit()
        }

        var lastSent = 0L
        var total = 0L
        val job = api().upload(file, "clip.mp4") { sent, totalBytes ->
            lastSent = sent
            total = totalBytes
        }

        assertEquals("job-1", job.id)
        assertEquals(file.length(), lastSent)
        assertEquals(file.length(), total)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/analyses", request.path)
        assertTrue(request.getHeader("Content-Type")?.startsWith("multipart/form-data") == true)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"clip.mp4\""))
    }

    @Test
    fun `upload sends hoop roi form field`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {"id":"job-2","status":"queued","filename":"clip.mp4",
                     "created_at":"2026-11-05T00:00:00Z","result":null,"error":null}
                    """.trimIndent(),
                ),
        )
        val file = File.createTempFile("clip", ".mp4").apply {
            writeBytes(ByteArray(16))
            deleteOnExit()
        }

        api().upload(
            file = file,
            filename = "clip.mp4",
            hoopRoi = HoopRoi(0.4f, 0.2f, 0.1f, 0.05f),
        ) { _, _ -> }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("name=\"hoop_roi\""))
        assertTrue(body.contains("0.40000,0.20000,0.10000,0.05000"))
    }

    @Test
    fun `413 maps to ApiException with status code`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(413)
                .setBody("""{"detail":"视频超过大小限制"}"""),
        )
        val file = File.createTempFile("clip", ".mp4").apply {
            writeBytes(ByteArray(16))
            deleteOnExit()
        }
        try {
            api().upload(file, "clip.mp4") { _, _ -> }
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(413, error.statusCode)
            assertEquals("视频超过大小限制", error.detail)
        }
    }

    @Test
    fun `404 job maps to ApiException`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail":"分析任务不存在"}"""),
        )
        try {
            api().getJob("missing")
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `health parses version and detector`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","service":"basketball","version":"0.1.0","detector":"stub"}""",
            ),
        )
        val health = api().health()
        assertEquals("0.1.0", health.version)
        assertEquals("stub", health.detector)
    }

    @Test
    fun `clip url points at the job clip endpoint`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')

        assertEquals(
            "$base/api/v1/analyses/job-1/clips/2",
            api().clipUrl("job-1", 2),
        )
    }

    @Test
    fun `download clip writes the body and reports progress`() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(payload)),
        )
        val destination = File.createTempFile("clip", ".mp4").apply {
            delete()
            deleteOnExit()
        }
        var received = 0L
        var total: Long? = null

        api().downloadClip("job-1", 1, destination) { bytes, totalBytes ->
            received = bytes
            total = totalBytes
        }

        assertArrayEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong(), received)
        assertEquals(payload.size.toLong(), total)
        val request = server.takeRequest()
        assertEquals("/api/v1/analyses/job-1/clips/1", request.path)
        assertEquals("GET", request.method)
    }

    @Test
    fun `download clip maps 404 to an ApiException`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail":"片段文件已清理"}"""),
        )
        try {
            api().downloadClip(
                "job-1",
                0,
                File.createTempFile("clip", ".mp4").apply { delete() },
            ) { _, _ -> }
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.statusCode)
            assertEquals("片段已失效", error.message)
            assertEquals("片段文件已清理", error.detail)
        }
    }

    private fun api(): AnalysisApi = AnalysisApi(
        json = Json { ignoreUnknownKeys = true },
        baseUrlProvider = { server.url("/").toString().trimEnd('/') },
    )
}
