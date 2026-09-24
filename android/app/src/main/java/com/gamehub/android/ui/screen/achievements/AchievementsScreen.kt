package com.gamehub.android.ui.screen.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.R
import com.gamehub.android.data.remote.Achievement
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.EmptyState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState

@Composable
fun AchievementsRoute(viewModel: AchievementsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AchievementsScreen(state = state, onRetry = viewModel::load)
}

@Composable
fun AchievementsScreen(
    state: UiState<AchievementsUiModel>,
    onRetry: () -> Unit,
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

        is UiState.Success -> {
            if (state.data.isEmpty) {
                EmptyState(stringResource(R.string.state_empty_achievements), modifier)
                return
            }

            Column(modifier.fillMaxSize()) {
                if (state.data.showsProgress) {
                    ProgressHeader(state.data)
                }

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.data.unlocked.isNotEmpty()) {
                        item { SectionHeader("Unlocked", state.data.unlocked.size) }
                        items(state.data.unlocked, key = { it.id }) { AchievementCard(it) }
                    }

                    // Locked achievements are the point of the screen, not an
                    // afterthought: they are what the player is working
                    // towards.
                    if (state.data.locked.isNotEmpty()) {
                        item { SectionHeader("Still to earn", state.data.locked.size) }
                        items(state.data.locked, key = { it.id }) { AchievementCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressHeader(model: AchievementsUiModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "${model.earnedPoints} of ${model.totalPoints} points",
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(
                progress = { model.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun AchievementCard(achievement: Achievement) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = achievement.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    // Dimmed rather than hidden or greyed to a silhouette.
                    // The player should be able to read what it is in order
                    // to decide whether to chase it.
                    .alpha(if (achievement.unlocked) 1f else 0.4f),
            )

            Column(Modifier.weight(1f)) {
                Text(achievement.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    achievement.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${achievement.rarity.lowercase().replaceFirstChar { it.uppercase() }} · " +
                        "${achievement.points} points",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
