package de.doorzign.app.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.doorzign.app.R
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.displays.DisplayDetailScreen
import de.doorzign.app.ui.editor.SlotEditorScreen
import de.doorzign.app.ui.displays.DisplayListScreen
import de.doorzign.app.ui.settings.SettingsScreen
import de.doorzign.app.ui.adhoc.AdhocScreen
import de.doorzign.app.ui.jobs.JobsScreen
import de.doorzign.app.ui.schedules.SchedulesScreen

object Routes {
    const val DISPLAYS = "displays"
    const val JOBS = "jobs"
    const val SETTINGS = "settings"
    const val DISPLAY = "display/{id}"
    const val SLOT = "display/{id}/slot/{slot}"
    const val ADHOC = "display/{id}/adhoc"
    const val SCHEDULES = "display/{id}/schedules"
    const val DISPLAY_JOBS = "display/{id}/jobs"

    fun display(id: String) = "display/${enc(id)}"
    fun slot(id: String, slot: Int) = "display/${enc(id)}/slot/$slot"
    fun adhoc(id: String) = "display/${enc(id)}/adhoc"
    fun schedules(id: String) = "display/${enc(id)}/schedules"
    fun displayJobs(id: String) = "display/${enc(id)}/jobs"
    private fun enc(s: String) = android.net.Uri.encode(s)
}

private data class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Hauptbereich nach der Anmeldung. Jede Navigationsstation hat eigene ViewModels (gebunden an [conn]). */
@Composable
fun MainNav(conn: Connection) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab(Routes.DISPLAYS, R.string.nav_displays, Icons.Default.Tv),
        Tab(Routes.JOBS, R.string.nav_jobs, Icons.Default.SwapVert),
        Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
    )
    val entry by nav.currentBackStackEntryAsState()
    val showBar = entry?.destination?.route in tabs.map { it.route }

    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = entry?.destination?.hierarchy?.any { it.route == tab.route } == true,
                    onClick = {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(tab.icon, null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = Routes.DISPLAYS, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            val idArg = navArgument("id") { type = NavType.StringType }
            composable(Routes.DISPLAYS) { DisplayListScreen(conn) { nav.navigate(Routes.display(it)) } }
            composable(Routes.JOBS) { JobsScreen(conn, null, null) }
            composable(Routes.SETTINGS) { SettingsScreen(conn) }
            composable(Routes.DISPLAY, listOf(idArg)) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                DisplayDetailScreen(
                    conn, id,
                    onBack = { nav.popBackStack() },
                    onEditSlot = { nav.navigate(Routes.slot(id, it)) },
                    onAdhoc = { nav.navigate(Routes.adhoc(id)) },
                    onSchedules = { nav.navigate(Routes.schedules(id)) },
                    onJobs = { nav.navigate(Routes.displayJobs(id)) },
                )
            }
            composable(Routes.SLOT, listOf(idArg, navArgument("slot") { type = NavType.IntType })) { e ->
                SlotEditorScreen(conn, e.arguments?.getString("id").orEmpty(), e.arguments?.getInt("slot") ?: 1) { nav.popBackStack() }
            }
            composable(Routes.ADHOC, listOf(idArg)) { e -> AdhocScreen(conn, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable(Routes.SCHEDULES, listOf(idArg)) { e -> SchedulesScreen(conn, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable(Routes.DISPLAY_JOBS, listOf(idArg)) { e -> JobsScreen(conn, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
        }
    }
}
