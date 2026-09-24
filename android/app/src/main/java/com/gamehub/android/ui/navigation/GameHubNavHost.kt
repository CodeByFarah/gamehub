package com.gamehub.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gamehub.android.ui.screen.achievements.AchievementsRoute
import com.gamehub.android.ui.screen.assistant.AssistantRoute
import com.gamehub.android.ui.screen.cloudsaves.CloudSavesRoute
import com.gamehub.android.ui.screen.discover.DiscoverRoute
import com.gamehub.android.ui.screen.gamedetail.GameDetailRoute
import com.gamehub.android.ui.screen.home.HomeRoute
import com.gamehub.android.ui.screen.leaderboard.LeaderboardRoute
import com.gamehub.android.ui.screen.matchmaking.MatchmakingRoute
import com.gamehub.android.ui.screen.profile.ProfileRoute
import com.gamehub.android.ui.screen.settings.SettingsScreen

/**
 * The navigation graph.
 *
 * <h3>Argument handling</h3>
 * Screens read their arguments from `SavedStateHandle` inside the ViewModel,
 * not from the composable. That survives process death, so returning to the
 * app after Android reclaims memory restores the same game or leaderboard
 * rather than a blank one.
 *
 * <h3>Optional arguments</h3>
 * Achievements and the assistant both accept an optional `gameId`. They are
 * declared as query parameters with `nullable = true` rather than as two
 * separate routes, because the screen is the same one either way and
 * duplicating it would mean duplicating every future change to it.
 *
 * <h3>Why routes are strings</h3>
 * Type-safe routes exist in Navigation Compose 2.8 and would be the better
 * choice in a greenfield app. They are not used here because the argument
 * surface is one nullable string, and the migration would add ceremony
 * without removing a failure mode this app actually has.
 */
@Composable
fun GameHubNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {

        composable(Routes.HOME) {
            HomeRoute(
                onGameClick = { navController.navigate(Routes.gameDetail(it)) },
            )
        }

        composable(Routes.DISCOVER) {
            DiscoverRoute(
                onGameClick = { navController.navigate(Routes.gameDetail(it)) },
            )
        }

        composable(Routes.PLAY) {
            MatchmakingRoute()
        }

        composable(Routes.PROFILE) {
            ProfileRoute(
                onAchievements = { navController.navigate(Routes.achievements()) },
                onCloudSaves = { navController.navigate(Routes.CLOUD_SAVES) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.GAME_DETAIL,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType }),
        ) {
            GameDetailRoute(
                onLeaderboard = { navController.navigate(Routes.leaderboard(it)) },
                // Matchmaking currently picks its own game. Passing the id
                // through is the next step; routing there is still correct.
                onFindMatch = { navController.navigate(Routes.PLAY) },
                onAchievements = { navController.navigate(Routes.achievements(it)) },
            )
        }

        composable(
            route = Routes.LEADERBOARD,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType }),
        ) {
            LeaderboardRoute()
        }

        composable(Routes.LEADERBOARD_GLOBAL) {
            LeaderboardRoute()
        }

        composable(
            route = Routes.ACHIEVEMENTS,
            arguments = listOf(
                navArgument("gameId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            AchievementsRoute()
        }

        composable(Routes.CLOUD_SAVES) {
            CloudSavesRoute()
        }

        composable(
            route = Routes.ASSISTANT,
            arguments = listOf(
                navArgument("gameId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            AssistantRoute(gameId = entry.arguments?.getString("gameId"))
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}
