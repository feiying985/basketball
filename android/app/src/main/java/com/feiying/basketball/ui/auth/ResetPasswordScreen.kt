package com.feiying.basketball.ui.auth

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.AuthResult
import com.feiying.basketball.ui.common.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ResetPasswordScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val authRepository = container.authRepository
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableStateOf(0) }
    var sendingCode by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seconds) {
        if (seconds > 0) {
            delay(1_000)
            seconds -= 1
        }
    }

    fun sendCode() {
        if (sendingCode || seconds > 0) return
        if (!isPhone(phone)) {
            error = "请输入正确的手机号"
            return
        }
        sendingCode = true
        error = null
        scope.launch {
            when (val result = authRepository.sendCode(phone, "reset")) {
                is AuthResult.Success -> seconds = result.value.resendAfter
                is AuthResult.Failure -> error = result.error.message
            }
            sendingCode = false
        }
    }

    fun submit() {
        if (loading) return
        error = null
        when {
            !isPhone(phone) -> error = "请输入正确的手机号"
            code.length != 6 -> error = "请输入 6 位验证码"
            newPassword.length < 8 -> error = "新密码需要至少 8 个字符"
            newPassword != confirm -> error = "两次输入的新密码不一致"
            else -> {
                loading = true
                scope.launch {
                    when (
                        val result = authRepository.resetPassword(
                            phone,
                            code,
                            newPassword,
                        )
                    ) {
                        is AuthResult.Success -> {
                            snackbar.showSnackbar("密码已重置，请用新密码登录")
                            onBack()
                        }

                        is AuthResult.Failure -> {
                            loading = false
                            error = result.error.message
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            ScreenHeader(title = stringResource(R.string.auth_reset_title), onBack = onBack)
            Spacer(Modifier.height(20.dp))
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
                    onClick = { sendCode() },
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
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it; error = null },
                label = { Text(stringResource(R.string.auth_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text(stringResource(R.string.auth_confirm_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
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
                    Text(stringResource(R.string.auth_reset_button))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
