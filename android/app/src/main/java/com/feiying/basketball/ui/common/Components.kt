package com.feiying.basketball.ui.common

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.feiying.basketball.R
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.ShotEvent

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        } else {
            Box(Modifier.size(48.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun StatusBadge(status: AnalysisStatus, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = status.badgeColor().copy(alpha = 0.14f),
    ) {
        Text(
            text = status.label(),
            style = MaterialTheme.typography.labelMedium,
            color = status.badgeColor(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    showController: Boolean = true,
    headers: Map<String, String> = emptyMap(),
    onPlayerReady: ((ExoPlayer) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(uri, headers) {
        val builder = ExoPlayer.Builder(context)
        if (headers.isNotEmpty()) {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }
        builder.build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        onPlayerReady?.invoke(player)
        onDispose { player.release() }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = showController
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A compact timeline with one marker per shot. Tapping a marker seeks to one second before
 * the event so the user sees the build-up, not just the instant of the shot.
 */
@Composable
fun ShotTimeline(
    shots: List<ShotEvent>,
    durationMs: Long?,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSeconds = maxOf(
        durationMs?.div(1000.0) ?: 0.0,
        shots.maxOfOrNull { it.timestampSeconds } ?: 0.0,
        1.0,
    )
    val description = stringResource(R.string.result_timeline)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = description },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(shots, totalSeconds) {
                    detectTapGestures { offset ->
                        val nearest = shots.minByOrNull { shot ->
                            kotlin.math.abs(shot.timestampSeconds / totalSeconds * widthPx - offset.x)
                        }
                        if (nearest != null) onSeek(nearest.timestampSeconds)
                    }
                },
        ) {
            val centerY = size.height / 2
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            shots.forEach { shot ->
                val x = (shot.timestampSeconds / totalSeconds * size.width).toFloat()
                    .coerceIn(0f, size.width)
                drawCircle(
                    color = if (shot.made) Color(0xFFC1440E) else Color.Gray,
                    radius = 6.dp.toPx(),
                    center = Offset(x, centerY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = Offset(x, centerY),
                )
            }
        }
    }
}
