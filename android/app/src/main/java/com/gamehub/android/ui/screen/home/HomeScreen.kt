package com.gamehub.android.ui.screen.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.R
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.data.remote.Recommendation
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.EmptyState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState
import com.gamehub.android.ui.component.StaleBanner

@Composable
fun HomeRoute(
    onGameClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onRetry = viewModel::refresh, onGameClick = onGameClick)
}

@Composable
fun HomeScreen(
    state: UiState<HomeUiModel>,
    onRetry: () -> Unit,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UiState.Loading -> LoadingState(modifier)

        is UiState.Error -> ErrorState(
            kind = state.kind,
            canRetry = state.canRetry,
            onRetry = onRetry,
            modifier = modifier,
        )

        is UiState.Success -> Column(modifier.fillMaxSize()) {
            if (state.isStale) StaleBanner()

            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                state.data.profile?.let { profile ->
                    item { ProfileHeader(profile) }
                }

                // The section is absent, not empty, when the call failed.
                // An empty "Recommended for you" implies the system has
                // nothing to suggest, which is a different and wrong message.
                if (!state.data.recommendationsUnavailable &&
                    state.data.recommendations.isNotEmpty()
                ) {
                    item {
                        SectionHeader("Recommended for you")
                        RecommendationRow(state.data.recommendations, onGameClick)
                    }
                }

                if (state.data.popular.isNotEmpty()) {
                    item { SectionHeader("Popular now") }
                    items(state.data.popular, key = { it.id }) { game ->
                        PopularRow(game) { onGameClick(game.id) }
                    }
                }

                if (state.data.isEmpty) {
                    item { EmptyState(stringResource(R.string.state_empty_games)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(profile.displayName, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Level ${profile.level} · ${profile.skillRating} rating · " +
                    "${profile.gamesWon}/${profile.gamesPlayed} won",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Progress within the current level, not lifetime XP. A bar that
            // only ever creeps rightwards tells the player nothing about how
            // close the next level is.
            val progress = (profile.xp % XP_PER_LEVEL) / XP_PER_LEVEL.toFloat()
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecommendationRow(
    recommendations: List<Recommendation>,
    onGameClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(recommendations, key = { it.game.id }) { recommendation ->
            Card(
                modifier = Modifier
                    .width(160.dp)
                    .clickable { onGameClick(recommendation.game.id) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    AsyncImage(
                        model = recommendation.game.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        recommendation.game.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    // The server always supplies a reason. Showing it is what
                    // makes a recommendation something the player can judge
                    // rather than a list they have to trust blindly.
                    Text(
                        recommendation.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun PopularRow(game: GameSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = game.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(game.title, style = MaterialTheme.typography.titleLarge)
            Text(
                game.shortDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Matches the backend XP curve. Kept here only for the progress bar. */
private const val XP_PER_LEVEL = 100
