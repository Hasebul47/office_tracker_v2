package com.officetracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Company
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Dates
import com.officetracker.ui.admin.EmployeeScreen
import com.officetracker.ui.admin.PlacesScreen
import com.officetracker.ui.admin.TeamScreen
import com.officetracker.ui.components.Banner
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.day.DayDetailScreen
import com.officetracker.ui.history.HistoryScreen
import com.officetracker.ui.profile.ProfileScreen
import com.officetracker.ui.tenant.SubscriptionScreen
import com.officetracker.ui.theme.Brand
import com.officetracker.ui.today.TodayScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

object Routes {
    const val TODAY = "today"
    const val HISTORY = "history"
    const val TEAM = "team"
    const val PLACES = "places"
    const val PROFILE = "profile"
    const val SUBSCRIPTION = "subscription"
    const val DAY = "day/{uid}/{date}"
    const val EMPLOYEE = "employee/{uid}"

    fun day(uid: String, date: String) = "day/$uid/$date"
    fun employee(uid: String) = "employee/$uid"
}

/** The company app (administrators and employees). [company] updates live from Firestore. */
@Composable
fun MainShell(profile: UserProfile, company: Company, platform: PlatformConfig) {
    val nav = rememberNavController()
    val features = company.features
    val tabs = buildList {
        add(Tab(Routes.TODAY, "Today", Icons.Default.Today))
        if (profile.isAdmin) {
            add(Tab(Routes.TEAM, "Team", Icons.Default.Groups))
            if (features.places) add(Tab(Routes.PLACES, "Places", Icons.Default.Place))
        } else {
            add(Tab(Routes.HISTORY, "History", Icons.Default.History))
        }
        add(Tab(Routes.PROFILE, "Profile", Icons.Default.Person))
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }
    val now = rememberNow(60_000)
    var dismissedAnnouncement by remember(platform.announcement) { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            if (showBar) {
                Column {
                    if (platform.announcement.isNotBlank() && !dismissedAnnouncement) {
                        Banner(
                            platform.announcement, Icons.Default.Campaign, Brand.Primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            actionLabel = "Hide", onAction = { dismissedAnnouncement = true },
                        )
                    }
                    PlanBanner(profile, company, now) { nav.navigate(Routes.SUBSCRIPTION) }
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
                TeamScreen(company = company, onOpenEmployee = { uid -> nav.navigate(Routes.employee(uid)) })
            }
            composable(Routes.PLACES) { PlacesScreen() }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    profile = profile,
                    company = company,
                    onOpenMyHistory = { nav.navigate(Routes.HISTORY) },
                    onOpenSubscription = { nav.navigate(Routes.SUBSCRIPTION) },
                )
            }
            composable(Routes.SUBSCRIPTION) {
                SubscriptionScreen(company = company, platform = platform, onBack = { nav.popBackStack() })
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

/** Trial-ending and payment-due reminders, shown to company administrators only. */
@Composable
private fun PlanBanner(profile: UserProfile, company: Company, now: Long, onOpen: () -> Unit) {
    if (!profile.isAdmin) return
    val access = company.access(now)
    val days = company.daysLeft(now)
    val text = when {
        access == AccessState.GRACE ->
            "Your plan has expired. Access stops on ${Dates.shortDay(Dates.keyOf(company.accessUntil))} unless renewed."
        access == AccessState.TRIAL && days <= 7 -> "Trial ends in $days day${if (days == 1L) "" else "s"}."
        access == AccessState.ACTIVE && days <= 7 -> "Plan renews in $days day${if (days == 1L) "" else "s"}. Renew to avoid interruption."
        else -> return
    }
    Banner(
        text, Icons.Default.Warning, if (access == AccessState.GRACE) Brand.Danger else Brand.Warning,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        actionLabel = "Details", onAction = onOpen,
    )
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
