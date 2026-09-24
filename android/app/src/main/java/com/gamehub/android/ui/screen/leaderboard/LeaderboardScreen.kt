package com.gamehub.android.ui.screen.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.R
import com.gamehub.android.data.remote.LeaderboardEntry
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.EmptyState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState

@Composable
fun LeaderboardRoute(viewModel: LeaderboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LeaderboardScreen(
        state = state,
        onScopeChange = viewModel::selectScope,
        onRetry = viewModel::load,
    )
}

@Composable
fun LeaderboardScreen(
    state: UiState<LeaderboardUiModel>,
    onScopeChange: (LeaderboardScope) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        val model = (state as? UiState.Success)?.data

        // Tabs stay visible during load and error, so switching scope is still
        // possible when one of them fails. Hiding them would strand the player
        // on a broken scope with no way out but Back.
        ScopeTabs(
            selected = model?.scope ?: LeaderboardScope.GLOBAL,
            gameScopeAvailable = model?.gameScopeAvailable ?: false,
            onScopeChange = onScopeChange,
        )

        when (state) {
            is UiState.Loading -> LoadingState()

            is UiState.Error -> ErrorState(
                kind = state.kind,
                canRetry = state.canRetry,
                onRetry = onRetry,
            )

            is UiState.Success -> {
                if (state.data.servedFromCache) {
                    CachedStandingsNotice()
                }

                if (state.data.entries.isEmpty()) {
                    EmptyState(stringResource(R.string.state_empty_leaderboard))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(state.data.entries, key = { it.userId }) { entry ->
                            LeaderboardRow(
                                entry = entry,
                                isViewer = entry.userId == state.data.viewerEntry?.userId,
                            )
                            HorizontalDivider()
                        }
                    }
                }

                // Pinned only when the viewer is ranked but off the current
                // page. Showing it twice when they are already visible would
                // be noise.
                val viewer = state.data.viewerEntry
                if (viewer != null && state.data.entries.none { it.userId == viewer.userId }) {
                    ViewerPin(viewer)
                }
            }
        }
    }
}

@Composable
private fun ScopeTabs(
    selected: LeaderboardScope,
    gameScopeAvailable: Boolean,
    onScopeChange: (LeaderboardScope) -> Unit,
) {
    val scopes = buildList {
        // Offered only when a game id is actually in scope, so the tab cannot
        // be tapped into a request the server will reject.
        if (gameScopeAvailable) add(LeaderboardScope.GAME)
        add(LeaderboardScope.REGIONAL)
        add(LeaderboardScope.GLOBAL)
    }

    TabRow(selectedTabIndex = scopes.indexOf(selected).coerceAtLeast(0)) {
        scopes.forEach { scope ->
            Tab(
                selected = scope == selected,
                onClick = { onScopeChange(scope) },
                text = {
                    Text(
                        when (scope) {
                            LeaderboardScope.GAME -> "This game"
                            LeaderboardScope.REGIONAL -> "Region"
                            LeaderboardScope.GLOBAL -> "Global"
                        },
                    )
                },
            )
        }
    }
}

/**
 * Shown when the server answered from Postgres rather than Redis.
 *
 * The data is correct; only the path was slower. Saying so turns an invisible
 * degradation into something a player can understand and a support case can
 * reference.
 */
@Composable
private fun CachedStandingsNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "Standings are being rebuilt and may load slowly",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, isViewer: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isViewer) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = entry.rank.toString(),
            // Fixed width so ranks stay in a column as digit counts change.
            // Without it, rank 9 becoming rank 10 visibly shifts every name.
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (entry.rank <= 3) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
        )

        AsyncImage(
            model = entry.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )

        Column(Modifier.weight(1f)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            entry.region?.let {
                Text(
                    it.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(text = entry.score.toString(), style = MaterialTheme.typography.titleLarge)
    }
}

/** The standing of the current player, pinned when it falls off the page. */
@Composable
private fun ViewerPin(entry: LeaderboardEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
    ) {
        LeaderboardRow(entry = entry, isViewer = true)
    }
}
