package com.feiying.basketball.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.feiying.basketball.R
import com.feiying.basketball.data.AuthRepository
import com.feiying.basketball.data.AuthResult
import com.feiying.basketball.ui.common.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun ChangePasswordRoute(
    authRepository: AuthRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            ScreenHeader(title = stringResource(R.string.change_password_title), onBack = onBack)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = current,
                onValueChange = { current = it; error = null },
                label = { Text(stringResource(R.string.change_password_current)) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) {
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
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it; error = null },
                label = { Text(stringResource(R.string.change_password_new)) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text(stringResource(R.string.change_password_confirm)) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
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
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !saving,
                onClick = {
                    when {
                        current.length < 8 -> error = "请输入当前密码"
                        newPassword.length < 8 -> error = "新密码需要至少 8 个字符"
                        newPassword != confirm -> error = "两次输入的新密码不一致"
                        else -> {
                            saving = true
                            scope.launch {
                                when (
                                    val result = authRepository.changePassword(
                                        current,
                                        newPassword,
                                    )
                                ) {
                                    is AuthResult.Success -> {
                                        snackbar.showSnackbar(
                                            "密码已修改，其他设备已退出登录",
                                        )
                                        onBack()
                                    }

                                    is AuthResult.Failure -> {
                                        saving = false
                                        error = result.error.message
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.change_password_save))
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
