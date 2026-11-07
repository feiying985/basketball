package com.feiying.basketball.ui.home

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.HealthState
import com.feiying.basketball.data.ImportErrorReason
import com.feiying.basketball.data.VideoFiles
import com.feiying.basketball.data.VideoImportException
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.ui.common.label
import com.feiying.basketball.ui.common.messageResource
import com.feiying.basketball.ui.common.summaryText
import com.feiying.basketball.ui.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    container: AppContainer,
    navController: NavHostController,
    onOpenAccount: () -> Unit,
) {
    val repository = container.repository
    val records by repository.records.collectAsStateWithLifecycle()
    val health by repository.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCaptureSheet by rememberSaveable { mutableStateOf(false) }
    var showDeveloperSettings by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = VideoFiles.importFromGallery(context, uri)
            importing = false
            result.onSuccess { video ->
                container.newAnalysisSession.select(video)
                navController.navigate(Routes.REVIEW)
            }.onFailure { error ->
                val reason = (error as? VideoImportException)?.reason
                    ?: ImportErrorReason.UNREADABLE
                snackbarHostState.showSnackbar(context.getString(reason.messageResource()))
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { repository.checkHealth() }
    }

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            records = records,
            health = health,
            onStartAnalysis = { showCaptureSheet = true },
            onOpenRecord = { record ->
                val target = when (record.status) {
                    AnalysisStatus.COMPLETED,
                    AnalysisStatus.FAILED,
                    AnalysisStatus.EXPIRED,
                    -> Routes.result(record.localId)

                    else -> Routes.analysis(record.localId)
                }
                navController.navigate(target)
            },
            onCheckHealth = { scope.launch { repository.checkHealth() } },
            onDeveloperSettings = { showDeveloperSettings = true },
            onOpenAccount = onOpenAccount,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
        if (importing) {
            ImportingOverlay()
        }
    }

    if (showCaptureSheet) {
        CaptureSheet(
            hasCamera = hasCamera,
            onDismiss = { showCaptureSheet = false },
            onRecord = {
                showCaptureSheet = false
                navController.navigate(Routes.RECORD)
            },
            onGallery = {
                showCaptureSheet = false
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            },
        )
    }

    if (showDeveloperSettings) {
        DeveloperSettingsDialog(
            repository = repository,
            health = health,
            onDismiss = { showDeveloperSettings = false },
            onSaved = {
                showDeveloperSettings = false
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.dev_saved))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    records: List<AnalysisRecord>,
    health: HealthState,
    onStartAnalysis: () -> Unit,
    onOpenRecord: (AnalysisRecord) -> Unit,
    onCheckHealth: () -> Unit,
    onDeveloperSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = records.firstOrNull()
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenAccount) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = stringResource(R.string.account_title),
                )
            }
        }
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(28.dp))
        if (latest == null) {
            Text(
                text = stringResource(R.string.home_first_steps),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.home_no_records),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            RecentRecordCard(record = latest, onClick = { onOpenRecord(latest) })
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStartAnalysis,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AddCircle,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.home_start_analysis),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(24.dp))
        ServiceStatusRow(
            health = health,
            onCheck = onCheckHealth,
            onLongPress = onDeveloperSettings,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RecentRecordCard(record: AnalysisRecord, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_recent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Formatters.historyTime(record.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.status.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.home_recent_open),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = record.summaryText(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (record.status == AnalysisStatus.UPLOADING ||
                    record.status == AnalysisStatus.QUEUED ||
                    record.status == AnalysisStatus.PROCESSING
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServiceStatusRow(
    health: HealthState,
    onCheck: () -> Unit,
    onLongPress: () -> Unit,
) {
    val statusText = when (health) {
        HealthState.Unknown -> stringResource(R.string.home_server_unknown)
        HealthState.Checking -> stringResource(R.string.home_server_checking)
        is HealthState.Online -> stringResource(R.string.home_server_online)
        is HealthState.Offline -> stringResource(R.string.home_server_offline)
    }
    val hint = when (health) {
        HealthState.Checking -> ""
        is HealthState.Offline -> stringResource(R.string.home_server_retry_hint)
        else -> stringResource(R.string.home_server_check_hint)
    }
    val dotColor = when (health) {
        is HealthState.Online -> MaterialTheme.colorScheme.primary
        is HealthState.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCheck, onLongClick = onLongPress),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = "${stringResource(R.string.home_server_label)} · $statusText",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (health) {
                    is HealthState.Online -> Text(
                        text = stringResource(
                            R.string.home_server_version,
                            health.version,
                            health.detector,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is HealthState.Offline -> Text(
                        text = health.detail ?: stringResource(R.string.home_server_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )

                    else -> Unit
                }
            }
            if (health == HealthState.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImportingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.capture_importing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureSheet(
    hasCamera: Boolean,
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            CaptureOption(
                icon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                title = stringResource(R.string.capture_record_title),
                description = if (hasCamera) {
                    stringResource(R.string.capture_record_desc)
                } else {
                    stringResource(R.string.capture_camera_unavailable)
                },
                enabled = hasCamera,
                onClick = onRecord,
            )
            CaptureOption(
                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                title = stringResource(R.string.capture_gallery_title),
                description = stringResource(R.string.capture_gallery_desc),
                enabled = true,
                onClick = onGallery,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CaptureOption(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { icon() }
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
