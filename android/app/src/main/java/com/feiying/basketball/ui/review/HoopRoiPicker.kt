package com.feiying.basketball.ui.review

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiying.basketball.R
import com.feiying.basketball.domain.HoopRoi
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_ROI_RATIO = 0.02f
private val SELECTION_COLOR = Color(0xFFFF6B35)

/** Extracts a still frame so the user can draw the hoop on the real video. */
object VideoFrameExtractor {
    suspend fun firstFrame(file: File): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return@withContext null
            // The metadata rotation must be applied manually: OpenCV applies
            // rotation metadata on the server, so the ROI coordinates only
            // line up when the preview uses the same orientation.
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            if (rotation % 360 == 0) {
                raw
            } else {
                Bitmap.createBitmap(
                    raw,
                    0,
                    0,
                    raw.width,
                    raw.height,
                    Matrix().apply { postRotate(rotation.toFloat()) },
                    true,
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable
fun HoopRoiPickerDialog(
    bitmap: Bitmap,
    initial: HoopRoi?,
    onDismiss: () -> Unit,
    onConfirm: (HoopRoi) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            HoopRoiPickerContent(
                bitmap = bitmap,
                initial = initial,
                onCancel = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
fun HoopRoiPickerContent(
    bitmap: Bitmap,
    initial: HoopRoi?,
    onCancel: () -> Unit,
    onConfirm: (HoopRoi) -> Unit,
) {
    val aspectRatio = remember(bitmap) {
        if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 16f / 9f
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(boxSize, initial) {
        if (boxSize != IntSize.Zero && initial != null && selection == null) {
            selection = Rect(
                initial.x * boxSize.width,
                initial.y * boxSize.height,
                (initial.x + initial.width) * boxSize.width,
                (initial.y + initial.height) * boxSize.height,
            )
        }
    }

    val liveRect = if (dragStart != null && dragCurrent != null) {
        rectFrom(dragStart!!, dragCurrent!!)
    } else {
        null
    }
    val shownRect = liveRect ?: selection
    val normalized = shownRect?.toHoopRoi(boxSize)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.hoop_roi_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.hoop_roi_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .onSizeChanged { boxSize = it }
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset.clampTo(size)
                            dragCurrent = dragStart
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragCurrent = ((dragCurrent ?: dragStart ?: change.position) + dragAmount)
                                .clampTo(size)
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragCurrent
                            selection = if (start != null && end != null) {
                                rectFrom(start, end).takeIf { rect ->
                                    rect.width >= MIN_ROI_RATIO * size.width &&
                                        rect.height >= MIN_ROI_RATIO * size.height
                                }
                            } else {
                                null
                            }
                            dragStart = null
                            dragCurrent = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        },
                    )
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.hoop_roi_title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Canvas(Modifier.fillMaxSize()) {
                val rect = shownRect ?: return@Canvas
                val dim = Color.Black.copy(alpha = 0.45f)
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, rect.top))
                drawRect(
                    dim,
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)),
                )
                drawRect(dim, topLeft = Offset(0f, rect.top), size = Size(rect.left, rect.height))
                drawRect(
                    dim,
                    topLeft = Offset(rect.right, rect.top),
                    size = Size((size.width - rect.right).coerceAtLeast(0f), rect.height),
                )
                drawRect(
                    color = SELECTION_COLOR,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Text(
            text = if (normalized != null) {
                stringResource(R.string.hoop_roi_selected)
            } else {
                stringResource(R.string.hoop_roi_empty)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (normalized != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    selection = null
                    dragStart = null
                    dragCurrent = null
                },
                enabled = shownRect != null,
            ) {
                Text(stringResource(R.string.hoop_roi_reset))
            }
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { normalized?.let(onConfirm) },
                enabled = normalized?.isValid == true,
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        }
        Text(
            text = stringResource(R.string.hoop_roi_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(0.dp))
    }
}

private fun Offset.clampTo(size: IntSize): Offset = Offset(
    x.coerceIn(0f, size.width.toFloat()),
    y.coerceIn(0f, size.height.toFloat()),
)

private fun rectFrom(start: Offset, end: Offset): Rect = Rect(
    left = min(start.x, end.x),
    top = min(start.y, end.y),
    right = max(start.x, end.x),
    bottom = max(start.y, end.y),
)

private fun Rect.toHoopRoi(boxSize: IntSize): HoopRoi? {
    if (boxSize.width <= 0 || boxSize.height <= 0) return null
    val width = (right - left) / boxSize.width
    val height = (bottom - top) / boxSize.height
    if (width < MIN_ROI_RATIO || height < MIN_ROI_RATIO) return null
    val roi = HoopRoi(
        x = (left / boxSize.width).coerceIn(0f, 1f),
        y = (top / boxSize.height).coerceIn(0f, 1f),
        width = width.coerceIn(0f, 1f),
        height = height.coerceIn(0f, 1f),
    )
    return roi.takeIf { it.isValid }
}
