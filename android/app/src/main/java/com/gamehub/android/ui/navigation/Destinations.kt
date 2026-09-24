package com.gamehub.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.gamehub.android.R

/**
 * Every route in one place.
 *
 * Raw string routes are error-prone: a typo in a navigate call compiles fine
 * and fails at runtime. Constants plus builder functions make the route set
 * enumerable and a rename a compile error.
 *
 * Optional arguments are query parameters rather than separate routes, so the
 * same screen is not declared twice.
 */
object Routes {

    // Bottom-bar destinations
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val PLAY = "play"
    const val PROFILE = "profile"

    // Contextual destinations
    const val GAME_DETAIL = "game/{gameId}"
    const val LEADERBOARD = "leaderboard/{gameId}"
    const val LEADERBOARD_GLOBAL = "leaderboard"
    const val ACHIEVEMENTS = "achievements?gameId={gameId}"
    const val CLOUD_SAVES = "cloud-saves"
    const val ASSISTANT = "assistant?gameId={gameId}"
    const val SETTINGS = "settings"
    const val SIGN_IN = "sign-in"

    fun gameDetail(gameId: String) = "game/$gameId"

    fun leaderboard(gameId: String) = "leaderboard/$gameId"

    /**
     * @param gameId null lists only what the player has unlocked across the
     *        catalogue. With a game, the screen also shows what is still to
     *        earn, which is only a meaningful target within one game.
     */
    fun achievements(gameId: String? = null) =
        if (gameId == null) "achievements" else "achievements?gameId=$gameId"

    fun assistant(gameId: String? = null) =
        if (gameId == null) "assistant" else "assistant?gameId=$gameId"
}

/**
 * The four bottom-bar destinations.
 *
 * Four, not eight. Material guidance caps a bottom bar at five, and beyond
 * that the targets get too small to hit reliably. Leaderboards, achievements,
 * cloud saves and the assistant are reached from context instead, which is
 * also where they are meaningful: a leaderboard for a game you are looking at
 * says more than a global list reached from a tab.
 */
enum class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
) {
    HOME(Routes.HOME, Icons.Default.Home, R.string.nav_home),
    DISCOVER(Routes.DISCOVER, Icons.Default.Search, R.string.nav_discover),
    PLAY(Routes.PLAY, Icons.Default.EmojiEvents, R.string.nav_play),
    PROFILE(Routes.PROFILE, Icons.Default.Person, R.string.nav_profile),
}
