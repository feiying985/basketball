package com.feiying.basketball.ui.result

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.domain.AnalysisMode
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.ui.common.ScreenHeader
import com.feiying.basketball.ui.common.ShotTimeline
import com.feiying.basketball.ui.common.VideoPlayer
import com.feiying.basketball.ui.common.message
import com.feiying.basketball.ui.navigation.Routes
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ResultRoute(
    container: AppContainer,
    navController: NavHostController,
    recordId: String,
) {
    val records by container.repository.records.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.localId == recordId }
    var waited by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(700)
        waited = true
    }

    LaunchedEffect(record?.status, retrying) {
        if (!retrying) return@LaunchedEffect
        when (record?.status) {
            AnalysisStatus.UPLOADING -> {
                retrying = false
                navController.navigate(Routes.analysis(recordId)) {
                    popUpTo(Routes.RESULT_PATTERN) { inclusive = true }
                }
            }

            AnalysisStatus.FAILED, AnalysisStatus.EXPIRED -> retrying = false
            else -> Unit
        }
    }

    when {
        record == null && !waited -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        record == null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
            ) {
                ScreenHeader(
                    title = stringResource(R.string.result_title),
                    onBack = { navController.popBackStack() },
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.result_not_found),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        else -> {
            val current = record ?: return
            if (current.result != null &&
                (current.status == AnalysisStatus.COMPLETED ||
                    current.status == AnalysisStatus.EXPIRED)
            ) {
                ResultContent(
                    record = current,
                    onBack = { navController.popBackStack() },
                    onOpenClip = { index ->
                        navController.navigate(Routes.clip(current.localId, index))
                    },
                    onReanalyze = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onDone = { navController.popBackStack() },
                )
            } else {
                ResultUnavailableContent(
                    record = current,
                    onBack = { navController.popBackStack() },
                    onOpenAnalysis = {
                        navController.navigate(Routes.analysis(current.localId)) {
                            popUpTo(Routes.RESULT_PATTERN) { inclusive = true }
                        }
                    },
                    onReanalyze = {
                        retrying = true
                        container.repository.retry(current.localId)
                    },
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
fun ResultContent(
    record: AnalysisRecord,
    onBack: () -> Unit,
    onOpenClip: (Int) -> Unit,
    onReanalyze: () -> Unit,
    onDone: () -> Unit,
) {
    val result = record.result ?: return
    // The server sorts shots by timestamp and clips are indexed by this order;
    // do not re-sort locally or the clip index would point at another shot.
    val shots = result.shots
    val readyClipIndexes = result.readyClipIndexes
    val videoFile = record.localVideoPath?.let(::File)?.takeIf { it.exists() }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    fun seekToShot(shot: ShotEvent) {
        val targetMs = ((shot.timestampSeconds - 1.0).coerceAtLeast(0.0) * 1000).toLong()
        player?.seekTo(targetMs)
        player?.play()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(title = stringResource(R.string.result_title), onBack = onBack)
        }
        if (record.status == AnalysisStatus.EXPIRED) {
            item {
                InfoBanner(stringResource(R.string.result_expired_snapshot))
            }
        }
        if (record.mode == AnalysisMode.ON_DEVICE) {
            item {
                InfoBanner(stringResource(R.string.result_local_banner))
            }
        }
        item {
            if (shots.isEmpty()) {
                EmptyResultCard(note = result.note)
            } else {
                SummaryCard(
                    total = shots.size,
                    made = shots.count { it.made },
                    note = result.note,
                )
            }
        }
        item {
            if (videoFile != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    color = Color.Black,
                ) {
                    VideoPlayer(
                        uri = Uri.fromFile(videoFile),
                        modifier = Modifier.fillMaxSize(),
                        onPlayerReady = { player = it },
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.result_video_cleaned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (shots.isNotEmpty()) {
            if (readyClipIndexes.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.result_clips_title,
                                readyClipIndexes.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(readyClipIndexes, key = { it }) { index ->
                                ClipCard(
                                    index = index,
                                    shot = shots[index],
                                    onClick = { onOpenClip(index) },
                                )
                            }
                        }
                    }
                }
            } else if (result.clipNote != null || result.hasClipAttempt) {
                item {
                    Text(
                        text = result.clipNote?.let {
                            stringResource(R.string.result_clips_note, it)
                        } ?: stringResource(R.string.result_clips_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.result_events_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.result_timeline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ShotTimeline(
                        shots = shots,
                        durationMs = record.durationMs,
                        onSeek = { seconds ->
                            player?.seekTo((seconds.coerceAtLeast(0.0) * 1000).toLong())
                            player?.play()
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            itemsIndexed(shots) { index, shot ->
                ShotRow(
                    index = index,
                    shot = shot,
                    onClick = { seekToShot(shot) },
                    onOpenClip = { onOpenClip(index) },
                )
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.result_empty_suggestions),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(3) { index ->
                val text = when (index) {
                    0 -> stringResource(R.string.result_tip_1)
                    1 -> stringResource(R.string.result_tip_2)
                    else -> stringResource(R.string.result_tip_3)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.result_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onReanalyze,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.result_analyze_again))
                }
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(total: Int, made: Int, note: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(
                    R.string.result_summary,
                    total,
                    made,
                    Formatters.percent(made, total),
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (!note.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.result_note, note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyResultCard(note: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.result_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!note.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.result_note, note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ClipCard(index: Int, shot: ShotEvent, onClick: () -> Unit) {
    val clip = shot.clip ?: return
    Card(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.result_shot_number, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = "${Formatters.shotTime(clip.startSeconds)}–" +
                    Formatters.shotTime(clip.endSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = Formatters.clipDuration(clip.durationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ShotRow(
    index: Int,
    shot: ShotEvent,
    onClick: () -> Unit,
    onOpenClip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.result_shot_number, index + 1),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = Formatters.shotTime(shot.timestampSeconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Icon(
            imageVector = if (shot.made) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (shot.made) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(
                if (shot.made) R.string.result_made else R.string.result_missed,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (shot.made) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(start = 6.dp),
        )
        if (shot.made && shot.clip?.isReady == true) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenClip) {
                Text(
                    text = stringResource(R.string.result_clip_open),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun ResultUnavailableContent(
    record: AnalysisRecord,
    onBack: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onReanalyze: () -> Unit,
    onDone: () -> Unit,
) {
    val text = when (record.status) {
        AnalysisStatus.EXPIRED -> stringResource(R.string.result_expired_no_snapshot)
        AnalysisStatus.FAILED -> record.errorCode?.message(record.errorDetail)
            ?: stringResource(R.string.result_failed)

        AnalysisStatus.UPLOADING,
        AnalysisStatus.UPLOAD_INTERRUPTED,
        AnalysisStatus.QUEUED,
        AnalysisStatus.PROCESSING,
        -> stringResource(R.string.analysis_opening_result)

        AnalysisStatus.COMPLETED -> stringResource(R.string.result_not_found)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.result_title), onBack = onBack)
        Spacer(Modifier.height(24.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(1f))
        if (record.status == AnalysisStatus.UPLOADING ||
            record.status == AnalysisStatus.UPLOAD_INTERRUPTED ||
            record.status == AnalysisStatus.QUEUED ||
            record.status == AnalysisStatus.PROCESSING
        ) {
            Button(
                onClick = onOpenAnalysis,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.result_open_analysis))
            }
        } else {
            Button(
                onClick = onReanalyze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.result_analyze_again))
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
        Spacer(Modifier.height(16.dp))
    }
}
