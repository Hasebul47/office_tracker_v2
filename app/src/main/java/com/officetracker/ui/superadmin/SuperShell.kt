package com.officetracker.ui.superadmin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.officetracker.core.model.UserProfile
import com.officetracker.ui.admin.EmployeeScreen
import com.officetracker.ui.navigateTab

private object SuperRoutes {
    const val OVERVIEW = "sa/overview"
    const val COMPANIES = "sa/companies"
    const val PLANS = "sa/plans"
    const val SETTINGS = "sa/settings"
    const val COMPANY = "sa/company/{cid}"
    const val PERSON = "sa/person/{uid}"

    fun company(cid: String) = "sa/company/$cid"
    fun person(uid: String) = "sa/person/$uid"
}

private data class SuperTab(val route: String, val label: String, val icon: ImageVector)

/** Platform owner's app: customers, plans, subscriptions and platform switches. */
@Composable
fun SuperShell(profile: UserProfile) {
    val nav = rememberNavController()
    val tabs = listOf(
        SuperTab(SuperRoutes.OVERVIEW, "Overview", Icons.Default.Dashboard),
        SuperTab(SuperRoutes.COMPANIES, "Companies", Icons.Default.Business),
        SuperTab(SuperRoutes.PLANS, "Plans", Icons.Default.Sell),
        SuperTab(SuperRoutes.SETTINGS, "Platform", Icons.Default.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = SuperRoutes.OVERVIEW, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(SuperRoutes.OVERVIEW) {
                DashboardScreen(profile, onOpenCompany = { nav.navigate(SuperRoutes.company(it)) }, onOpenCompanies = { nav.navigateTab(SuperRoutes.COMPANIES) })
            }
            composable(SuperRoutes.COMPANIES) {
                CompaniesScreen(onOpenCompany = { nav.navigate(SuperRoutes.company(it)) })
            }
            composable(SuperRoutes.PLANS) { PlansScreen() }
            composable(SuperRoutes.SETTINGS) { PlatformSettingsScreen(profile) }
            composable(SuperRoutes.COMPANY, arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
                CompanyDetailScreen(
                    companyId = entry.arguments?.getString("cid").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenPerson = { nav.navigate(SuperRoutes.person(it)) },
                )
            }
            composable(SuperRoutes.PERSON, arguments = listOf(navArgument("uid") { type = NavType.StringType })) { entry ->
                EmployeeScreen(viewer = profile, uid = entry.arguments?.getString("uid").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}
