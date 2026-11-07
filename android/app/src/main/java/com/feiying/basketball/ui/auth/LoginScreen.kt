package com.feiying.basketball.ui.auth

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.AuthResult
import com.feiying.basketball.ui.home.DeveloperSettingsDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    container: AppContainer,
    onRegister: () -> Unit,
    onReset: () -> Unit,
) {
    val authRepository = container.authRepository
    val scope = rememberCoroutineScope()
    val health by container.repository.health.collectAsStateWithLifecycle()
    var passwordTab by rememberSaveable { mutableStateOf(true) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var sendingCode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeveloperSettings by remember { mutableStateOf(false) }

    LaunchedEffect(seconds) {
        if (seconds > 0) {
            delay(1_000)
            seconds -= 1
        }
    }

    fun sendLoginCode() {
        if (sendingCode || seconds > 0) return
        if (!isPhone(phone)) {
            error = "请输入正确的手机号"
            return
        }
        sendingCode = true
        error = null
        scope.launch {
            when (val result = authRepository.sendCode(phone, "login")) {
                is AuthResult.Success -> seconds = result.value.resendAfter
                is AuthResult.Failure -> error = result.error.message
            }
            sendingCode = false
        }
    }

    fun submit() {
        if (loading) return
        error = null
        if (passwordTab) {
            if (account.isBlank()) {
                error = "请输入手机号或用户名"
                return
            }
            if (password.length < 8) {
                error = "密码需要至少 8 个字符"
                return
            }
            loading = true
            scope.launch {
                when (val result = authRepository.login(account.trim(), password)) {
                    is AuthResult.Failure -> {
                        loading = false
                        error = result.error.message
                    }

                    is AuthResult.Success -> Unit
                }
            }
        } else {
            if (!isPhone(phone)) {
                error = "请输入正确的手机号"
                return
            }
            if (code.length != 6) {
                error = "请输入 6 位验证码"
                return
            }
            loading = true
            scope.launch {
                when (val result = authRepository.loginWithCode(phone, code)) {
                    is AuthResult.Failure -> {
                        loading = false
                        error = result.error.message
                    }

                    is AuthResult.Success -> Unit
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showDeveloperSettings = true },
                ),
            )
            Text(
                text = stringResource(R.string.auth_login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = passwordTab,
                    onClick = {
                        passwordTab = true
                        error = null
                    },
                    label = { Text(stringResource(R.string.auth_tab_password)) },
                )
                FilterChip(
                    selected = !passwordTab,
                    onClick = {
                        passwordTab = false
                        error = null
                    },
                    label = { Text(stringResource(R.string.auth_tab_code)) },
                )
            }
            Spacer(Modifier.height(20.dp))
            if (passwordTab) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it; error = null },
                    label = { Text(stringResource(R.string.auth_account_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.auth_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; error = null },
                    label = { Text(stringResource(R.string.auth_phone_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
                        label = { Text(stringResource(R.string.auth_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !sendingCode && seconds == 0,
                        onClick = { sendLoginCode() },
                    ) {
                        Text(
                            text = if (seconds > 0) {
                                stringResource(R.string.auth_resend_countdown, seconds)
                            } else {
                                stringResource(R.string.auth_send_code)
                            },
                        )
                    }
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.auth_login_button))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.auth_forgot_password))
                }
                TextButton(onClick = onRegister) {
                    Text(stringResource(R.string.auth_register_link))
                }
            }
        }
    }

    if (showDeveloperSettings) {
        DeveloperSettingsDialog(
            repository = container.repository,
            health = health,
            onDismiss = { showDeveloperSettings = false },
            onSaved = { showDeveloperSettings = false },
        )
    }
}

internal fun isPhone(value: String): Boolean {
    val digits = value.replace(" ", "").removePrefix("+86").removePrefix("86")
    return Regex("^1[3-9]\\d{9}$").matches(digits)
}
