package com.feiying.basketball.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.AuthState
import com.feiying.basketball.ui.auth.AuthNavHost

/** Routes the app between the login graph and the signed-in app. */
@Composable
fun AuthGate(container: AppContainer) {
    val state by container.authRepository.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        container.authRepository.restore()
    }

    when (state) {
        AuthState.Loading -> AuthSplash()
        AuthState.LoggedOut -> AuthNavHost(container)
        is AuthState.LoggedIn -> BasketballApp(container)
    }
}

@Composable
private fun AuthSplash() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
        }
    }
}
