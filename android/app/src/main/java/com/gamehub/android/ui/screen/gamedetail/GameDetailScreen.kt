package com.gamehub.android.ui.screen.gamedetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState

@Composable
fun GameDetailRoute(
    onLeaderboard: (String) -> Unit,
    onFindMatch: (String) -> Unit,
    onAchievements: (String) -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GameDetailScreen(
        state = state,
        onLeaderboard = { onLeaderboard(viewModel.gameId()) },
        onFindMatch = { onFindMatch(viewModel.gameId()) },
        onAchievements = { onAchievements(viewModel.gameId()) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameDetailScreen(
    state: UiState<GameSummary>,
    onLeaderboard: () -> Unit,
    onFindMatch: () -> Unit,
    onAchievements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UiState.Loading -> LoadingState(modifier)

        is UiState.Error -> ErrorState(
            kind = state.kind,
            // A delisted or missing game cannot be retried into existence.
            canRetry = state.canRetry,
            onRetry = {},
            modifier = modifier,
        )

        is UiState.Success -> {
            val game = state.data

            Column(
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = game.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                    )
                    Column {
                        Text(game.title, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${game.genre} · ${game.avgSessionMinutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The rating count matters as much as the average. A
                        // 5.0 from two players is not a 5.0 from twenty
                        // thousand, and hiding the count invites that
                        // misreading.
                        Text(
                            "${game.ratingAvg} from ${game.ratingCount} players",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(game.shortDescription, style = MaterialTheme.typography.bodyMedium)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    game.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }

                // Offered only when the game actually supports it. A Find
                // match button on a single-player game produces a 400 the
                // player can do nothing about.
                if (game.supportsMultiplayer) {
                    Button(onClick = onFindMatch, modifier = Modifier.fillMaxWidth()) {
                        Text("Find a match")
                    }
                }

                OutlinedButton(onClick = onLeaderboard, modifier = Modifier.fillMaxWidth()) {
                    Text("Leaderboard")
                }

                OutlinedButton(onClick = onAchievements, modifier = Modifier.fillMaxWidth()) {
                    Text("Achievements")
                }

                if (game.supportsCloudSave) {
                    Text(
                        "Progress syncs across your devices",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
