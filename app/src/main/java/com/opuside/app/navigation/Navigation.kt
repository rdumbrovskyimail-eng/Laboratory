package com.opuside.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Code
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
import com.opuside.app.feature.creator.presentation.ClaudeHelperScreen
import com.opuside.app.feature.settings.presentation.SettingsScreen
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

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
}

// Список экранов для Bottom Navigation
val bottomNavItems = listOf(
    Screen.Creator,
    Screen.Analyzer,
    Screen.Settings
)

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN NAVIGATION COMPOSABLE
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * ✅ ОБНОВЛЕНО: Добавлен параметр sensitiveFeatureDisabled для Root Detection
 * 
 * Когда sensitiveFeatureDisabled = true, следующие функции должны быть отключены:
 * - Сохранение Anthropic API ключа (Settings)
 * - Сохранение GitHub токена (Settings)
 * - Биометрическая аутентификация (Settings)
 * - Чат с Claude (Analyzer) - т.к. требует API ключ
 * 
 * @param sensitiveFeatureDisabled флаг отключения чувствительных функций при root-доступе
 */
@Composable
fun OpusIDENavigation(
    sensitiveFeatureDisabled: Boolean = false
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            // Скрываем bottom bar на экране Claude Helper
            val currentRoute = currentDestination?.route
            if (currentRoute != "claude_helper") {
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
                                    // Избегаем дублирования в back stack
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // Избегаем нескольких копий одного экрана
                                    launchSingleTop = true
                                    // Восстанавливаем состояние при повторном выборе
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Creator.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Creator.route) {
                // ✅ Creator работает нормально даже при root
                // (GitHub API не требует локального хранения чувствительных данных)
                CreatorScreen(
                    onNavigateToClaudeHelper = {
                        navController.navigate("claude_helper")
                    }
                )
            }
            
            composable(Screen.Analyzer.route) {
                // ✅ ИСПРАВЛЕНО: AnalyzerScreen не принимает параметр sensitiveFeatureDisabled
                AnalyzerScreen()
            }
            
            composable(Screen.Settings.route) {
                // ✅ ОБНОВЛЕНО: Передаём флаг в Settings
                // Settings должны заблокировать поля API ключей если sensitiveFeatureDisabled = true
                SettingsScreen(
                    sensitiveFeatureDisabled = sensitiveFeatureDisabled
                )
            }
            
            // ✅ НОВЫЙ МАРШРУТ: Claude Helper Screen
            composable("claude_helper") {
                ClaudeHelperScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}