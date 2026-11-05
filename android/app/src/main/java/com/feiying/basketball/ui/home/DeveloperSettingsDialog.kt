package com.feiying.basketball.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feiying.basketball.BuildConfig
import com.feiying.basketball.R
import com.feiying.basketball.data.AnalysisRepository
import com.feiying.basketball.data.HealthState
import kotlinx.coroutines.launch
import java.net.URL

/**
 * Hidden developer-only entry. Regular users never see the server URL; this dialog is
 * opened by long-pressing the service status row on the home screen.
 */
@Composable
fun DeveloperSettingsDialog(
    repository: AnalysisRepository,
    health: HealthState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        url = repository.currentBaseUrl()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dev_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dev_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        showError = false
                    },
                    label = { Text(stringResource(R.string.dev_server_url)) },
                    placeholder = { Text(stringResource(R.string.dev_server_url_hint)) },
                    isError = showError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.dev_invalid_url),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.dev_health),
                    style = MaterialTheme.typography.labelLarge,
                )
                when (health) {
                    is HealthState.Online -> Text(
                        stringResource(
                            R.string.dev_health_online,
                            health.version,
                            health.detector,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is HealthState.Offline -> Text(
                        stringResource(
                            R.string.dev_health_offline,
                            health.detail ?: stringResource(R.string.error_unknown),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    HealthState.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                    )

                    HealthState.Unknown -> Text(
                        stringResource(R.string.home_server_unknown),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        url = BuildConfig.SERVER_BASE_URL
                        showError = false
                    },
                ) {
                    Text(stringResource(R.string.dev_reset))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (!isValidBaseUrl(url)) {
                        showError = true
                        return@TextButton
                    }
                    saving = true
                    scope.launch {
                        repository.setBaseUrl(url.trim().trimEnd('/'))
                        saving = false
                        onSaved()
                    }
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun isValidBaseUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    return runCatching {
        val url = URL(trimmed)
        url.host.isNotBlank()
    }.getOrDefault(false)
}
