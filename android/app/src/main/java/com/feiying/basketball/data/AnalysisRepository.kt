package com.feiying.basketball.data

import android.os.SystemClock
import com.feiying.basketball.data.local.AnalysisRecordDao
import com.feiying.basketball.data.local.AnalysisRecordEntity
import com.feiying.basketball.domain.AnalysisError
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.ClipStatus
import com.feiying.basketball.domain.HoopRoi
import com.feiying.basketball.domain.PollingPolicy
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.ShotClip
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.domain.VideoSource
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed interface HealthState {
    data object Unknown : HealthState
    data object Checking : HealthState
    data class Online(val version: String, val detector: String) : HealthState
    data class Offline(val detail: String? = null) : HealthState
}

enum class ActivePhase {
    UPLOADING,
    QUEUED,
    PROCESSING,
    NETWORK_RETRY,
}

data class ActiveAnalysis(
    val phase: ActivePhase,
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val elapsedMs: Long = 0,
    val timedOut: Boolean = false,
    val stage: String? = null,
) {
    val progress: Float?
        get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Owns local history, uploads and job polling.
 *
 * Uploads and polling run in an application-scoped coroutine so navigating between screens
 * does not cancel them. Process death is handled by [recoverInterruptedUploads] on startup.
 */
class AnalysisRepository(
    private val dao: AnalysisRecordDao,
    private val api: AnalysisApi,
    private val settingsStore: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _records = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val records: StateFlow<List<AnalysisRecord>> = _records.asStateFlow()

    private val _active = MutableStateFlow<Map<String, ActiveAnalysis>>(emptyMap())
    val active: StateFlow<Map<String, ActiveAnalysis>> = _active.asStateFlow()

    private val _health = MutableStateFlow<HealthState>(HealthState.Unknown)
    val health: StateFlow<HealthState> = _health.asStateFlow()

    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val timeoutAcknowledged = ConcurrentHashMap.newKeySet<String>()
    private val resumedOnOpen = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var ownerUserId: String? = null
    private var observationJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch { checkHealth() }
    }

    /**
     * Switches the local history view to one account.  Legacy anonymous records
     * are claimed by the first account that signs in on this device.
     */
    suspend fun setOwner(ownerId: String?) {
        if (ownerId == ownerUserId) return
        ownerUserId = ownerId
        observationJob?.cancel()
        observationJob = null
        _records.value = emptyList()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        timeoutAcknowledged.clear()
        resumedOnOpen.clear()
        _active.value = emptyMap()
        if (ownerId == null) return
        dao.claimUnowned(ownerId)
        observationJob = scope.launch {
            dao.observeByOwner(ownerId).collect { entities ->
                _records.value = entities.map { it.toDomain() }
            }
        }
        recoverInterruptedUploads(ownerId)
    }

    suspend fun recoverInterruptedUploads(ownerId: String? = null) {
        val owner = ownerId ?: ownerUserId ?: return
        dao.updateStatusForAll(
            oldStatus = AnalysisStatus.UPLOADING.name,
            newStatus = AnalysisStatus.UPLOAD_INTERRUPTED.name,
            updatedAt = now(),
            ownerId = owner,
        )
    }

    private suspend fun ownedEntity(recordId: String): AnalysisRecordEntity? {
        val owner = ownerUserId ?: return null
        return dao.get(recordId, owner)
    }

    suspend fun checkHealth() {
        _health.value = HealthState.Checking
        _health.value = try {
            val info = api.health()
            HealthState.Online(info.version, info.detector)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            HealthState.Offline(error.message)
        }
    }

    suspend fun createRecord(video: SelectedVideo, hoopRoi: HoopRoi? = null): AnalysisRecord {
        val timestamp = now()
        val record = AnalysisRecord(
            localId = UUID.randomUUID().toString(),
            serverJobId = null,
            createdAt = timestamp,
            source = video.source,
            localVideoPath = video.file.absolutePath,
            filename = video.filename,
            sizeBytes = video.sizeBytes,
            durationMs = video.durationMs,
            status = AnalysisStatus.UPLOADING,
            hoopRoi = hoopRoi,
            updatedAt = timestamp,
        )
        dao.upsert(record.toEntity())
        return record
    }

    fun start(recordId: String) {
        if (jobs.containsKey(recordId)) return
        val job = scope.launch { runAnalysis(recordId) }
        jobs[recordId] = job
        job.invokeOnCompletion { jobs.remove(recordId, job) }
    }

    fun resume(recordId: String) {
        val record = _records.value.firstOrNull { it.localId == recordId } ?: return
        if (record.status == AnalysisStatus.QUEUED || record.status == AnalysisStatus.PROCESSING) {
            start(recordId)
        }
    }

    /** Called when a list showing in-progress records opens; each record resumes once per process. */
    fun resumeInProgressOnce(recordIds: Collection<String>) {
        recordIds.forEach { id ->
            if (resumedOnOpen.add(id)) resume(id)
        }
    }

    fun retry(recordId: String) {
        jobs.remove(recordId)?.cancel()
        timeoutAcknowledged.remove(recordId)
        resumedOnOpen.remove(recordId)
        scope.launch {
            val entity = ownedEntity(recordId) ?: return@launch
            val file = entity.localVideoPath?.let(::File)
            if (file == null || !file.exists()) {
                markFailed(recordId, AnalysisStatus.FAILED, AnalysisError.VIDEO_MISSING, null)
                return@launch
            }
            dao.resetForRetry(
                localId = recordId,
                status = AnalysisStatus.UPLOADING.name,
                updatedAt = now(),
            )
            _active.update { it - recordId }
            start(recordId)
        }
    }

    fun cancelUpload(recordId: String) {
        jobs.remove(recordId)?.cancel()
        timeoutAcknowledged.remove(recordId)
        _active.update { it - recordId }
        scope.launch {
            ownerUserId?.let { owner -> dao.delete(recordId, owner) }
        }
    }

    /** Continues waiting after the five-minute timeout warning. */
    fun continueWaiting(recordId: String) {
        timeoutAcknowledged.add(recordId)
        _active.update { current ->
            val state = current[recordId] ?: return@update current
            current + (recordId to state.copy(timedOut = false))
        }
    }

    fun delete(recordId: String, deleteVideo: Boolean = true) {
        jobs.remove(recordId)?.cancel()
        timeoutAcknowledged.remove(recordId)
        resumedOnOpen.remove(recordId)
        _active.update { it - recordId }
        scope.launch {
            val owner = ownerUserId ?: return@launch
            val entity = dao.get(recordId, owner)
            dao.delete(recordId, owner)
            if (deleteVideo) VideoFiles.deleteQuietly(entity?.localVideoPath)
        }
    }

    suspend fun currentBaseUrl(): String = settingsStore.baseUrl.first()

    suspend fun clipUrl(jobId: String, index: Int): String = api.clipUrl(jobId, index)

    suspend fun downloadClip(
        jobId: String,
        index: Int,
        destination: File,
        onProgress: (bytesReceived: Long, totalBytes: Long?) -> Unit,
    ): File = api.downloadClip(jobId, index, destination, onProgress)

    suspend fun setBaseUrl(url: String) {
        settingsStore.setBaseUrl(url)
        checkHealth()
    }

    private suspend fun runAnalysis(recordId: String) {
        val entity = ownedEntity(recordId) ?: return
        val status = entity.status.toStatus() ?: AnalysisStatus.FAILED
        if (status == AnalysisStatus.COMPLETED || status == AnalysisStatus.EXPIRED) return

        var jobId = entity.serverJobId
        if (jobId == null) {
            val file = entity.localVideoPath?.let(::File)
            if (file == null || !file.exists()) {
                markFailed(recordId, AnalysisStatus.FAILED, AnalysisError.VIDEO_MISSING, null)
                return
            }
            setActive(recordId, ActiveAnalysis(ActivePhase.UPLOADING, totalBytes = file.length()))
            try {
                val dto = api.upload(
                    file = file,
                    filename = entity.filename,
                    hoopRoi = HoopRoi.parse(entity.hoopRoi),
                ) { sent, total ->
                    setActive(
                        recordId,
                        ActiveAnalysis(
                            phase = ActivePhase.UPLOADING,
                            bytesSent = sent,
                            totalBytes = total,
                        ),
                    )
                }
                jobId = dto.id
                dao.markQueued(
                    localId = recordId,
                    serverJobId = dto.id,
                    status = AnalysisStatus.QUEUED.name,
                    updatedAt = now(),
                )
                setActive(recordId, ActiveAnalysis(ActivePhase.QUEUED))
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                markFailed(recordId, AnalysisStatus.FAILED, error.toAnalysisError(), error.detail)
                return
            } catch (error: IOException) {
                dao.markInterrupted(
                    localId = recordId,
                    expectedStatus = AnalysisStatus.UPLOADING.name,
                    status = AnalysisStatus.UPLOAD_INTERRUPTED.name,
                    errorCode = AnalysisError.UPLOAD_INTERRUPTED.name,
                    updatedAt = now(),
                )
                _active.update { it - recordId }
                return
            } catch (error: Exception) {
                markFailed(recordId, AnalysisStatus.FAILED, AnalysisError.UNKNOWN, error.message)
                return
            }
        } else {
            setActive(recordId, ActiveAnalysis(phaseFor(status)))
        }
        val jobIdToPoll = jobId ?: return
        poll(recordId, jobIdToPoll)
    }

    private suspend fun poll(recordId: String, jobId: String) {
        val startedAt = elapsedRealtime()
        var networkErrors = 0
        var firstRequest = true
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (!firstRequest) {
                val elapsed = elapsedRealtime() - startedAt
                delay(PollingPolicy.pollIntervalMs(elapsed))
            }
            firstRequest = false
            val elapsed = elapsedRealtime() - startedAt
            try {
                val dto = api.getJob(jobId)
                networkErrors = 0
                when (dto.status.lowercase()) {
                    "queued" -> {
                        dao.updateStatus(recordId, AnalysisStatus.QUEUED.name, now())
                        setActive(recordId, progressState(recordId, ActivePhase.QUEUED, elapsed))
                    }

                    "processing" -> {
                        dao.updateStatus(recordId, AnalysisStatus.PROCESSING.name, now())
                        setActive(
                            recordId,
                            progressState(
                                recordId,
                                ActivePhase.PROCESSING,
                                elapsed,
                                stage = dto.stage,
                            ),
                        )
                    }

                    "completed" -> {
                        val result = dto.result?.toDomain()
                        dao.markCompleted(
                            localId = recordId,
                            status = AnalysisStatus.COMPLETED.name,
                            resultJson = result?.let { json.encodeToString(AnalysisResult.serializer(), it) },
                            updatedAt = now(),
                        )
                        _active.update { it - recordId }
                        return
                    }

                    "failed" -> {
                        markFailed(
                            recordId,
                            AnalysisStatus.FAILED,
                            AnalysisError.ANALYSIS_FAILED,
                            dto.error,
                        )
                        return
                    }

                    else -> {
                        markFailed(
                            recordId,
                            AnalysisStatus.FAILED,
                            AnalysisError.SERVER_ERROR,
                            dto.status,
                        )
                        return
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.statusCode == 404) {
                    markFailed(recordId, AnalysisStatus.EXPIRED, AnalysisError.JOB_EXPIRED, error.detail)
                    return
                }
                networkErrors++
                if (networkErrors >= PollingPolicy.MAX_CONSECUTIVE_SERVER_ERRORS) {
                    markFailed(recordId, AnalysisStatus.FAILED, AnalysisError.SERVER_ERROR, error.detail)
                    return
                }
                setActive(
                    recordId,
                    progressState(recordId, ActivePhase.NETWORK_RETRY, elapsed),
                )
                delay(PollingPolicy.networkBackoffMs(networkErrors))
            } catch (error: IOException) {
                networkErrors++
                setActive(
                    recordId,
                    progressState(recordId, ActivePhase.NETWORK_RETRY, elapsed),
                )
                delay(PollingPolicy.networkBackoffMs(networkErrors))
            } catch (error: Exception) {
                markFailed(recordId, AnalysisStatus.FAILED, AnalysisError.UNKNOWN, error.message)
                return
            }
        }
    }

    private fun progressState(
        recordId: String,
        phase: ActivePhase,
        elapsed: Long,
        stage: String? = null,
    ): ActiveAnalysis {
        val current = _active.value[recordId]
        val timedOut = PollingPolicy.isOverTotalTimeout(elapsed) &&
            recordId !in timeoutAcknowledged
        return ActiveAnalysis(
            phase = phase,
            bytesSent = current?.bytesSent ?: 0,
            totalBytes = current?.totalBytes ?: 0,
            elapsedMs = elapsed,
            timedOut = timedOut,
            stage = stage ?: current?.stage,
        )
    }

    private fun setActive(recordId: String, state: ActiveAnalysis) {
        _active.update { it + (recordId to state) }
    }

    private suspend fun markFailed(
        recordId: String,
        status: AnalysisStatus,
        error: AnalysisError,
        detail: String?,
    ) {
        dao.markFailed(
            localId = recordId,
            status = status.name,
            errorCode = error.name,
            errorDetail = detail,
            updatedAt = now(),
        )
        _active.update { it - recordId }
    }

    private fun phaseFor(status: AnalysisStatus): ActivePhase = when (status) {
        AnalysisStatus.QUEUED -> ActivePhase.QUEUED
        AnalysisStatus.PROCESSING -> ActivePhase.PROCESSING
        else -> ActivePhase.UPLOADING
    }

    private fun ApiException.toAnalysisError(): AnalysisError = when (statusCode) {
        413 -> AnalysisError.TOO_LARGE
        415 -> AnalysisError.UNSUPPORTED_FORMAT
        in 500..599 -> AnalysisError.SERVER_ERROR
        404 -> AnalysisError.JOB_EXPIRED
        else -> AnalysisError.UNKNOWN
    }

    private fun AnalysisRecordEntity.toDomain(): AnalysisRecord = AnalysisRecord(
        localId = localId,
        serverJobId = serverJobId,
        createdAt = createdAt,
        source = runCatching { VideoSource.valueOf(source) }.getOrDefault(VideoSource.GALLERY),
        localVideoPath = localVideoPath,
        filename = filename,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        status = status.toStatus() ?: AnalysisStatus.FAILED,
        hoopRoi = HoopRoi.parse(hoopRoi),
        result = resultJson?.let { stored ->
            runCatching {
                json.decodeFromString(AnalysisResult.serializer(), stored)
            }.getOrNull()
        },
        errorCode = errorCode?.let { runCatching { AnalysisError.valueOf(it) }.getOrNull() },
        errorDetail = errorDetail,
        updatedAt = updatedAt,
    )

    private fun AnalysisRecord.toEntity(): AnalysisRecordEntity = AnalysisRecordEntity(
        localId = localId,
        serverJobId = serverJobId,
        createdAt = createdAt,
        source = source.name,
        localVideoPath = localVideoPath,
        filename = filename,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        status = status.name,
        hoopRoi = hoopRoi?.toFormValue(),
        resultJson = result?.let { json.encodeToString(AnalysisResult.serializer(), it) },
        errorCode = errorCode?.name,
        errorDetail = errorDetail,
        updatedAt = updatedAt,
        ownerUserId = this@AnalysisRepository.ownerUserId,
    )

    private fun String.toStatus(): AnalysisStatus? =
        runCatching { AnalysisStatus.valueOf(this) }.getOrNull()

    private fun AnalysisResultDto.toDomain(): AnalysisResult = AnalysisResult(
        detector = detector,
        shots = shots.map {
            ShotEvent(
                timestampSeconds = it.timestampSeconds,
                frameIndex = it.frameIndex,
                made = it.made,
                clip = it.clip?.toDomain(),
            )
        },
        note = note,
        clipNote = clipNote,
    )

    private fun ShotClipDto.toDomain(): ShotClip = ShotClip(
        status = when (status.lowercase()) {
            "ready" -> ClipStatus.READY
            "failed" -> ClipStatus.FAILED
            else -> ClipStatus.UNKNOWN
        },
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        durationSeconds = durationSeconds,
        sizeBytes = sizeBytes,
        url = url,
        error = error,
    )
}
