package com.feiying.basketball.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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

    private fun api(): AnalysisApi = AnalysisApi(
        json = Json { ignoreUnknownKeys = true },
        baseUrlProvider = { server.url("/").toString().trimEnd('/') },
    )
}
