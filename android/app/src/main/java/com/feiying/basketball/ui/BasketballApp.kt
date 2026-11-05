package com.feiying.basketball.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SportsBasketball
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.ui.analysis.AnalysisRoute
import com.feiying.basketball.ui.history.HistoryRoute
import com.feiying.basketball.ui.home.HomeRoute
import com.feiying.basketball.ui.navigation.Routes
import com.feiying.basketball.ui.record.RecordRoute
import com.feiying.basketball.ui.result.ResultRoute
import com.feiying.basketball.ui.review.ReviewRoute

@Composable
fun BasketballApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    AppScaffold(
        currentRoute = currentRoute,
        onNavigate = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeRoute(container = container, navController = navController)
            }
            composable(Routes.HISTORY) {
                HistoryRoute(container = container, navController = navController)
            }
            composable(Routes.RECORD) {
                RecordRoute(
                    onClose = { navController.popBackStack() },
                    onUseVideo = { video ->
                        container.newAnalysisSession.select(video)
                        navController.navigate(Routes.REVIEW) {
                            popUpTo(Routes.RECORD) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.REVIEW) {
                ReviewRoute(
                    container = container,
                    navController = navController,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.ANALYSIS_PATTERN,
                arguments = listOf(navArgument(Routes.RECORD_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                val recordId = entry.arguments?.getString(Routes.RECORD_ID_ARG).orEmpty()
                AnalysisRoute(
                    container = container,
                    navController = navController,
                    recordId = recordId,
                )
            }
            composable(
                route = Routes.RESULT_PATTERN,
                arguments = listOf(navArgument(Routes.RECORD_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                val recordId = entry.arguments?.getString(Routes.RECORD_ID_ARG).orEmpty()
                ResultRoute(
                    container = container,
                    navController = navController,
                    recordId = recordId,
                )
            }
        }
    }
}

/** Shared app shell; also used by screenshot tests so the bottom bar is part of the capture. */
@Composable
fun AppScaffold(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val showBottomBar = currentRoute == Routes.HOME || currentRoute == Routes.HISTORY
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { onNavigate(Routes.HOME) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SportsBasketball,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.tab_analyze)) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.HISTORY,
                        onClick = { onNavigate(Routes.HISTORY) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.tab_history)) },
                    )
                }
            }
        },
        content = content,
    )
}
