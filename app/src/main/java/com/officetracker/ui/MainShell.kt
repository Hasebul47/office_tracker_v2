package com.officetracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.officetracker.core.model.UserProfile
import com.officetracker.ui.admin.EmployeeScreen
import com.officetracker.ui.admin.PlacesScreen
import com.officetracker.ui.admin.TeamScreen
import com.officetracker.ui.day.DayDetailScreen
import com.officetracker.ui.history.HistoryScreen
import com.officetracker.ui.profile.ProfileScreen
import com.officetracker.ui.today.TodayScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

object Routes {
    const val TODAY = "today"
    const val HISTORY = "history"
    const val TEAM = "team"
    const val PLACES = "places"
    const val PROFILE = "profile"
    const val DAY = "day/{uid}/{date}"
    const val EMPLOYEE = "employee/{uid}"

    fun day(uid: String, date: String) = "day/$uid/$date"
    fun employee(uid: String) = "employee/$uid"
}

@Composable
fun MainShell(profile: UserProfile) {
    val nav = rememberNavController()
    val tabs = buildList {
        add(Tab(Routes.TODAY, "Today", Icons.Default.Today))
        if (profile.isAdmin) {
            add(Tab(Routes.TEAM, "Team", Icons.Default.Groups))
            add(Tab(Routes.PLACES, "Places", Icons.Default.Place))
        } else {
            add(Tab(Routes.HISTORY, "History", Icons.Default.History))
        }
        add(Tab(Routes.PROFILE, "Profile", Icons.Default.Person))
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.TODAY) {
                TodayScreen(
                    profile = profile,
                    onOpenHistory = { if (profile.isAdmin) nav.navigate(Routes.HISTORY) else nav.navigateTab(Routes.HISTORY) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    profile = profile,
                    onOpenDay = { date -> nav.navigate(Routes.day(profile.uid, date)) },
                    onBack = if (profile.isAdmin) ({ nav.popBackStack() }) else null,
                )
            }
            composable(Routes.TEAM) {
                TeamScreen(onOpenEmployee = { uid -> nav.navigate(Routes.employee(uid)) })
            }
            composable(Routes.PLACES) { PlacesScreen() }
            composable(Routes.PROFILE) {
                ProfileScreen(profile = profile, onOpenMyHistory = { nav.navigate(Routes.HISTORY) })
            }
            composable(
                Routes.DAY,
                arguments = listOf(navArgument("uid") { type = NavType.StringType }, navArgument("date") { type = NavType.StringType }),
            ) { entry ->
                DayDetailScreen(
                    viewer = profile,
                    uid = entry.arguments?.getString("uid").orEmpty(),
                    initialDate = entry.arguments?.getString("date").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.EMPLOYEE, arguments = listOf(navArgument("uid") { type = NavType.StringType })) { entry ->
                EmployeeScreen(
                    viewer = profile,
                    uid = entry.arguments?.getString("uid").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
