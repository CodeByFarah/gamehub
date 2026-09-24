package com.gamehub.android.ui.screen.matchmaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamehub.android.domain.ErrorKind
import com.gamehub.android.ui.component.ErrorState

@Composable
fun MatchmakingRoute(viewModel: MatchmakingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MatchmakingScreen(
        state = state,
        onJoin = { viewModel.join(DEMO_GAME_ID) },
        onLeave = viewModel::leave,
    )
}

/**
 * The matchmaking screen.
 *
 * The important behaviour here is what is shown while searching: **elapsed
 * seconds**, not an indeterminate spinner. A queue with no visible progress
 * feels broken after about ten seconds, and this queue can legitimately take
 * a minute in a thin population.
 */
@Composable
fun MatchmakingScreen(
    state: MatchmakingState,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Exhaustive by construction: MatchmakingState is sealed, so adding a
        // state breaks this when-expression rather than silently rendering
        // nothing.
        when (state) {
            MatchmakingState.Idle -> {
                Text(
                    "Find an opponent at your skill level",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onJoin, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Find match")
                }
            }

            MatchmakingState.Joining -> {
                CircularProgressIndicator()
                Text("Joining the queue", modifier = Modifier.padding(top = 16.dp))
            }

            is MatchmakingState.Searching -> {
                CircularProgressIndicator()

                Text(
                    "Searching for an opponent",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )

                // Concrete progress. The server reports waitedSeconds, so the
                // player sees the queue working rather than guessing.
                Text(
                    "${state.ticket.waitedSeconds}s",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Text(
                    "The skill range widens the longer you wait, so a match " +
                        "always arrives eventually",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )

                OutlinedButton(onClick = onLeave, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Cancel")
                }
            }

            is MatchmakingState.Matched -> {
                Text(
                    "Match found",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Opponents: ${state.ticket.opponentIds.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = { }, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Start")
                }
            }

            MatchmakingState.Expired -> {
                // Deliberately distinct from a failure. Nothing went wrong;
                // there were simply not enough players, so the useful action
                // is to try a different game rather than to retry this one.
                Text(
                    "Not enough players right now",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Try a different game, or try again in a few minutes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onJoin, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Search again")
                }
            }

            MatchmakingState.AlreadyQueued -> {
                // Not surfaced as an error. The player is queued, which is
                // what they asked for; this usually means a retried request
                // that had already succeeded.
                Text(
                    "You are already in the queue",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onLeave, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Leave the queue")
                }
            }

            is MatchmakingState.Failed -> ErrorState(
                kind = state.kind,
                canRetry = state.kind != ErrorKind.UNAUTHORIZED,
                onRetry = onJoin,
            )
        }
    }
}

/**
 * Ironroot Siege, from the seed catalogue.
 *
 * Hardcoded because this screen is reached from the bottom bar rather than
 * from a game. In a finished app the game would be chosen first.
 */
private const val DEMO_GAME_ID = "a0000000-0000-4000-8000-000000000003"
