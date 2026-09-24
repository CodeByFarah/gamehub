package com.gamehub.android.ui.screen.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.R
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.DegradedAiBadge
import com.gamehub.android.ui.component.EmptyState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState
import com.gamehub.android.ui.component.StaleBanner

/**
 * Discover: search, filter, and natural-language search.
 *
 * The composable takes state and callbacks, never the ViewModel itself. That
 * makes it previewable and testable without Hilt, and it keeps the decision
 * about *where* state comes from in one place.
 */
@Composable
fun DiscoverRoute(
    onGameClick: (String) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the latter keeps
    // collecting while the app is backgrounded, which wastes work and can
    // deliver updates to a screen nobody is looking at.
    val state by viewModel.state.collectAsStateWithLifecycle()

    DiscoverScreen(
        state = state,
        onSearch = viewModel::search,
        onAiSearch = viewModel::aiSearch,
        onRetry = viewModel::refresh,
        onGameClick = onGameClick,
    )
}

@Composable
fun DiscoverScreen(
    state: UiState<DiscoverUiModel>,
    onSearch: (String) -> Unit,
    onAiSearch: (String) -> Unit,
    onRetry: () -> Unit,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearch(it)
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search, or describe what you want to play") },
            singleLine = true,
            trailingIcon = {
                // The AI path is an explicit action, not an automatic one.
                // Sending every keystroke to a paid provider would be
                // expensive and slow; the player decides when it is worth it.
                IconButton(onClick = { onAiSearch(query) }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Search with AI")
                }
            },
        )

        when (state) {
            is UiState.Loading -> LoadingState()

            is UiState.Error -> ErrorState(
                kind = state.kind,
                canRetry = state.canRetry,
                onRetry = onRetry,
            )

            is UiState.Success -> {
                // Stale data is shown with a banner, not replaced by an error.
                if (state.isStale) {
                    StaleBanner()
                }

                state.data.aiIntent?.let { intent ->
                    AiInterpretation(
                        interpretation = intent.interpretation,
                        degraded = state.data.aiDegraded,
                    )
                }

                if (state.data.games.isEmpty()) {
                    EmptyState(stringResource(R.string.state_empty_games))
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Keyed by id so Compose reuses items across a
                        // refresh instead of recomposing the whole list and
                        // losing scroll position.
                        items(state.data.games, key = { it.id }) { game ->
                            GameCard(game = game, onClick = { onGameClick(game.id) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shows the system interpretation back to the player.
 *
 * A search that silently applies filters the player did not ask for is one
 * they cannot correct.
 */
@Composable
private fun AiInterpretation(interpretation: String?, degraded: Boolean) {
    if (interpretation.isNullOrBlank()) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = interpretation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (degraded) {
            DegradedAiBadge()
        }
    }
}

@Composable
private fun GameCard(game: GameSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = game.iconUrl,
                // Null rather than the title: the title is already read out
                // by the adjacent Text, and repeating it makes a screen
                // reader say everything twice.
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    game.shortDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    "${game.genre} · ${game.avgSessionMinutes} min" +
                        if (game.supportsMultiplayer) " · Multiplayer" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
