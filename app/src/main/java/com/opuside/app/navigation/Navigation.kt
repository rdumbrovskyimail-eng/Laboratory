package com.opuside.app.navigation

import com.opuside.app.core.ui.theme.AppTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opuside.app.feature.analyzer.presentation.AnalyzerScreen
import com.opuside.app.feature.creator.presentation.CreatorScreen
import com.opuside.app.feature.scratch.presentation.ScratchScreen
import com.opuside.app.feature.settings.presentation.SettingsScreen
import com.opuside.app.feature.workflows.presentation.WorkflowsScreen
import com.opuside.app.core.security.SecureSettingsDataStore

// ═══════════════════════════════════════════════════════════════════════════════
// NAVIGATION ROUTES
// ═══════════════════════════════════════════════════════════════════════════════

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    // ✅ НОВЫЙ ЭКРАН: Scratch — перед Creator
    data object Scratch : Screen(
        route = "scratch",
        title = "Scratch",
        selectedIcon = Icons.Filled.ContentPaste,
        unselectedIcon = Icons.Outlined.ContentPaste
    )

    data object Creator : Screen(
        route = "creator",
        title = "Creator",
        selectedIcon = Icons.Filled.Code,
        unselectedIcon = Icons.Outlined.Code
    )

    data object Analyzer : Screen(
        route = "analyzer",
        title = "Analyzer",
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics
    )

    data object Workflows : Screen(
        route = "workflows",
        title = "Actions",
        selectedIcon = Icons.Filled.PlayCircle,
        unselectedIcon = Icons.Outlined.PlayCircle
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
}

// Список экранов для Bottom Navigation (Scratch идёт первым)
val bottomNavItems = listOf(
    Screen.Creator,
    Screen.Analyzer,
    Screen.Workflows,
    Screen.Scratch,
    Screen.Settings
)

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN NAVIGATION COMPOSABLE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun OpusIDENavigation(
    sensitiveFeatureDisabled: Boolean = false,
    selectedTheme: AppTheme = AppTheme.GRAPHITE,
    onThemeChange: (AppTheme) -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon
                                             else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Creator.route,   // ← стартовый экран = Creator
            modifier = Modifier.padding(innerPadding)
        ) {
            // ✅ Scratch
            composable(Screen.Scratch.route) {
                ScratchScreen()
            }

            composable(Screen.Creator.route) {
                CreatorScreen()
            }

            composable(Screen.Analyzer.route) {
                AnalyzerScreen(
                    selectedTheme = selectedTheme,
                    onThemeChange = onThemeChange
                )
            }

            composable(Screen.Workflows.route) {
                WorkflowsScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    sensitiveFeatureDisabled = sensitiveFeatureDisabled,
                    selectedTheme = selectedTheme,
                    onThemeChange = onThemeChange
                )
            }
        }
    }
}
