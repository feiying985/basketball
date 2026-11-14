package com.feiying.basketball.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.feiying.basketball.data.local.AppDatabase
import com.feiying.basketball.domain.AnalysisMode
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.HoopRoi
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.domain.VideoSource
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the local analysis path never uploads the video and still persists a
 * normal result snapshot in local history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceAnalysisRepositoryTest {

    @Test
    fun localModeCompletesWithoutUploading() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","service":"basketball","version":"test","detector":"stub"}""",
            ),
        )
        server.start()
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedPaths.add(chain.request().url.encodedPath)
                chain.proceed(chain.request())
            }
            .build()
        val api = AnalysisApi(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            client = client,
        )
        var analyzerCalls = 0
        val analyzer = object : OnDeviceAnalyzer {
            override suspend fun analyze(
                videoFile: File,
                hoopRoi: HoopRoi?,
                durationMs: Long?,
                onProgress: (Float) -> Unit,
            ): AnalysisResult {
                analyzerCalls++
                onProgress(0.5f)
                return AnalysisResult(
                    detector = "on-device",
                    shots = listOf(ShotEvent(2.0, 20, made = true)),
                    clipNote = "local",
                )
            }
        }
        val repository = AnalysisRepository(
            dao = database.analysisRecordDao(),
            api = api,
            settingsStore = SettingsStore(context),
            onDeviceAnalyzer = analyzer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            now = { 1_000L },
            elapsedRealtime = { 0L },
        )
        val video = File(context.cacheDir, "local.mp4").apply { writeBytes(ByteArray(16)) }

        try {
            repository.setOwner("u1")
            val record = repository.createRecord(
                video = SelectedVideo(
                    file = video,
                    filename = "local.mp4",
                    sizeBytes = 16,
                    durationMs = 4_000,
                    source = VideoSource.GALLERY,
                ),
                hoopRoi = HoopRoi(0.4f, 0.2f, 0.2f, 0.1f),
                mode = AnalysisMode.ON_DEVICE,
            )
            repository.start(record.localId)

            withTimeout(5_000) {
                while (database.analysisRecordDao().get(record.localId, "u1")?.status !=
                    AnalysisStatus.COMPLETED.name
                ) {
                    delay(20)
                }
            }

            val stored = database.analysisRecordDao().get(record.localId, "u1")!!
            assertEquals(AnalysisMode.ON_DEVICE.name, stored.mode)
            val result = Json.decodeFromString<AnalysisResult>(stored.resultJson!!)
            assertEquals("on-device", result.detector)
            assertEquals(1, result.shots.size)
            assertEquals(1, analyzerCalls)
            assertTrue(
                "local analysis must not call the upload endpoint",
                requestedPaths.none { it.startsWith("/api/v1/analyses") },
            )
        } finally {
            database.close()
            server.shutdown()
        }
    }
}
