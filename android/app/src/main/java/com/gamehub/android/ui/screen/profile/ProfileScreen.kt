package com.gamehub.android.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gamehub.android.R
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState
import com.gamehub.android.ui.component.StaleBanner

@Composable
fun ProfileRoute(
    onAchievements: () -> Unit,
    onCloudSaves: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProfileScreen(
        state = state,
        onRetry = viewModel::refresh,
        onSignOut = viewModel::signOut,
        onAchievements = onAchievements,
        onCloudSaves = onCloudSaves,
        onSettings = onSettings,
    )
}

@Composable
fun ProfileScreen(
    state: UiState<UserProfile>,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onAchievements: () -> Unit,
    onCloudSaves: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UiState.Loading -> LoadingState(modifier)

        is UiState.Error -> ErrorState(
            kind = state.kind,
            canRetry = state.canRetry,
            onRetry = onRetry,
            onSignIn = onSignOut,
            modifier = modifier,
        )

        is UiState.Success -> Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.isStale) StaleBanner()

            Header(state.data)
            StatsGrid(state.data)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            TextButton(onClick = onAchievements, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_achievements))
            }
            TextButton(onClick = onCloudSaves, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_cloud_saves))
            }
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_settings))
            }

            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun Header(profile: UserProfile) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = profile.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Text(
            profile.displayName,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "@${profile.username} · ${profile.region.replace('_', ' ')}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsGrid(profile: UserProfile) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("Level", profile.level.toString(), Modifier.weight(1f))
        StatCard("Rating", profile.skillRating.toString(), Modifier.weight(1f))
        StatCard(
            "Win rate",
            // Zero games is 0 percent, not an error or a blank. The server
            // returns 0.0 for it deliberately.
            "${(profile.winRate * 100).toInt()}%",
            Modifier.weight(1f),
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("Played", profile.gamesPlayed.toString(), Modifier.weight(1f))
        StatCard("Won", profile.gamesWon.toString(), Modifier.weight(1f))
        StatCard(
            "Playtime",
            "${profile.totalPlaytimeSeconds / 3600}h",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
