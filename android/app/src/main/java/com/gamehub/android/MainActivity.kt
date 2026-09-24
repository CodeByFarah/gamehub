package com.gamehub.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gamehub.android.ui.navigation.GameHubNavHost
import com.gamehub.android.ui.navigation.TopLevelDestination
import com.gamehub.android.ui.theme.GameHubTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity.
 *
 * One activity with Compose navigation rather than an activity per screen:
 * shared state, transitions and back handling are all simpler when there is
 * one lifecycle to reason about.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draws behind the system bars. Scaffold supplies the insets, so
        // content is not hidden under the status or navigation bar.
        enableEdgeToEdge()

        setContent {
            GameHubTheme {
                GameHubApp()
            }
        }
    }
}

@Composable
private fun GameHubApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    // hierarchy, not a direct route comparison: a nested
                    // screen such as game detail should keep its parent tab
                    // highlighted rather than clearing the selection.
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Pops to the start destination so the back
                                // stack does not grow every time a tab is
                                // tapped, which would make Back walk through
                                // the whole tab history.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(destination.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        GameHubNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
