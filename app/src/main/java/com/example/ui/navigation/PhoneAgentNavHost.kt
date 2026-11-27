package com.example.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.PhoneAgentDatabase
import com.example.di.AppContainer
import com.example.game.GeneralGameEngine
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.ui.components.AgentSafetyWarningBanner
import com.example.ui.components.CyberGeometricBackground
import com.example.ui.screens.ActionLogScreen
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.CommandsScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.viewmodel.ActionLogViewModel
import com.example.ui.viewmodel.AppsViewModel
import com.example.ui.viewmodel.CommandsViewModel
import com.example.ui.viewmodel.PermissionsViewModel

sealed class AgentDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Commands : AgentDestination(
        route = "commands",
        labelRes = R.string.nav_commands,
        selectedIcon = Icons.Rounded.Mic,
        unselectedIcon = Icons.Outlined.Mic
    )

    data object Apps : AgentDestination(
        route = "apps",
        labelRes = R.string.nav_apps,
        selectedIcon = Icons.Rounded.Apps,
        unselectedIcon = Icons.Outlined.Apps
    )

    data object Logs : AgentDestination(
        route = "logs",
        labelRes = R.string.nav_logs,
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History
    )

    data object Permissions : AgentDestination(
        route = "permissions",
        labelRes = R.string.nav_permissions,
        selectedIcon = Icons.Rounded.Shield,
        unselectedIcon = Icons.Outlined.Shield
    )
}

@Composable
fun PhoneAgentApp(
    appContainer: AppContainer,
    applicationContext: android.content.Context
) {
    val navController = rememberNavController()
    val destinations = listOf(
        AgentDestination.Commands,
        AgentDestination.Apps,
        AgentDestination.Logs,
        AgentDestination.Permissions
    )

    val chatHistoryRepository = remember(applicationContext) {
        val db = PhoneAgentDatabase.getInstance(applicationContext)
        ChatHistoryRepository(db.chatMessageDao(), db.agentTaskDao())
    }
    val executionController = remember {
        AgentExecutionController()
    }
    val floatingControlManager = remember(applicationContext) {
        AgentFloatingControlManager(applicationContext)
    }
    val generalGameEngine = remember(applicationContext, appContainer, executionController, floatingControlManager) {
        GeneralGameEngine(
            context = applicationContext,
            geminiRepository = appContainer.geminiRepository,
            actionLogRepository = appContainer.actionLogRepository,
            agentPolicy = appContainer.agentPolicy,
            executionController = executionController,
            floatingControlManager = floatingControlManager
        )
    }

    val commandsViewModel: CommandsViewModel = viewModel(
        factory = CommandsViewModel.Factory(
            geminiRepository = appContainer.geminiRepository,
            commandExecutor = appContainer.commandExecutor,
            actionLogRepository = appContainer.actionLogRepository,
            appOrganizer = appContainer.appOrganizer,
            screenVerifier = appContainer.screenVerifier,
            voiceManager = appContainer.voiceAssistantManager,
            chatHistoryRepository = chatHistoryRepository,
            generalGameEngine = generalGameEngine,
            executionController = executionController,
            floatingControlManager = floatingControlManager,
            appContext = applicationContext,
            agentPolicy = appContainer.agentPolicy
        )
    )
    val appsViewModel: AppsViewModel = viewModel(
        factory = AppsViewModel.Factory(appContainer.appOrganizer)
    )
    val actionLogViewModel: ActionLogViewModel = viewModel(
        factory = ActionLogViewModel.Factory(
            actionLogRepository = appContainer.actionLogRepository,
            appOrganizer = appContainer.appOrganizer,
            commandExecutor = appContainer.commandExecutor
        )
    )
    val permissionsViewModel: PermissionsViewModel = viewModel(
        factory = PermissionsViewModel.Factory(applicationContext)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    CyberGeometricBackground {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail) {
                        Surface(
                            color = Color(0xF5F9F9FB),
                            border = BorderStroke(0.5.dp, CyberBorderGlow.copy(alpha = 0.75f))
                        ) {
                            NavigationBar(containerColor = Color.Transparent) {
                                destinations.forEach { dest ->
                                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                                    val label = stringResource(dest.labelRes)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTo(dest.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = label
                                            )
                                        },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CyberElectricCyan,
                                            selectedTextColor = CyberElectricCyan,
                                            indicatorColor = CyberElectricCyan.copy(alpha = 0.12f),
                                            unselectedIconColor = CyberTextSecondary,
                                            unselectedTextColor = CyberTextSecondary
                                        ),
                                        modifier = Modifier.testTag("nav_tab_${dest.route}")
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = Color(0xF5F9F9FB)) {
                            destinations.forEach { dest ->
                                val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                                val label = stringResource(dest.labelRes)
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTo(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = CyberElectricCyan,
                                        selectedTextColor = CyberElectricCyan,
                                        indicatorColor = CyberElectricCyan.copy(alpha = 0.12f),
                                        unselectedIconColor = CyberTextSecondary,
                                        unselectedTextColor = CyberTextSecondary
                                    ),
                                    modifier = Modifier.testTag("nav_rail_${dest.route}")
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // شريط التحذير الأصفر ثابت وظاهر دائماً في كل الشاشات الأربع (بما فيها شاشة الدردشة)
                        AgentSafetyWarningBanner()
                        HorizontalDivider(color = CyberBorderGlow.copy(alpha = 0.45f), thickness = 0.5.dp)

                        NavHost(
                            navController = navController,
                            startDestination = AgentDestination.Commands.route,
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            composable(AgentDestination.Commands.route) {
                                CommandsScreen(
                                    viewModel = commandsViewModel,
                                    onOpenPermissionsTab = { navigateTo(AgentDestination.Permissions.route) }
                                )
                            }
                            composable(AgentDestination.Apps.route) {
                                AppsScreen(
                                    viewModel = appsViewModel,
                                    onStartGameplayFromApps = {
                                        navigateTo(AgentDestination.Commands.route)
                                        commandsViewModel.startGameplayGoal()
                                    },
                                    onOpenPermissionsTab = { navigateTo(AgentDestination.Permissions.route) }
                                )
                            }
                            composable(AgentDestination.Logs.route) {
                                ActionLogScreen(viewModel = actionLogViewModel)
                            }
                            composable(AgentDestination.Permissions.route) {
                                PermissionsScreen(viewModel = permissionsViewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
