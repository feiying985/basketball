package com.feiying.basketball.ui.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.feiying.basketball.data.AppContainer

private object AuthRoutes {
    const val LOGIN = "auth/login"
    const val REGISTER = "auth/register"
    const val RESET = "auth/reset"
}

@Composable
fun AuthNavHost(container: AppContainer) {
    val navController = rememberNavController()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = AuthRoutes.LOGIN,
        ) {
            composable(AuthRoutes.LOGIN) {
                LoginScreen(
                    container = container,
                    onRegister = { navController.navigate(AuthRoutes.REGISTER) },
                    onReset = { navController.navigate(AuthRoutes.RESET) },
                )
            }
            composable(AuthRoutes.REGISTER) {
                RegisterScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AuthRoutes.RESET) {
                ResetPasswordScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
