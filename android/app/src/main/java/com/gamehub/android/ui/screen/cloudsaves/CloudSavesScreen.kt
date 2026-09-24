package com.gamehub.android.ui.screen.cloudsaves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamehub.android.R
import com.gamehub.android.data.remote.CloudSave
import com.gamehub.android.domain.UiState
import com.gamehub.android.ui.component.EmptyState
import com.gamehub.android.ui.component.ErrorState
import com.gamehub.android.ui.component.LoadingState

@Composable
fun CloudSavesRoute(viewModel: CloudSavesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()

    CloudSavesScreen(
        state = state,
        conflict = conflict,
        uploading = uploading,
        onRetry = viewModel::refresh,
        onKeepLocal = viewModel::keepLocal,
        onKeepServer = viewModel::keepServer,
        onDismissConflict = viewModel::dismissConflict,
    )
}

@Composable
fun CloudSavesScreen(
    state: UiState<List<CloudSave>>,
    conflict: ConflictPrompt?,
    uploading: Boolean,
    onRetry: () -> Unit,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
    onDismissConflict: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (uploading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        when (state) {
            is UiState.Loading -> LoadingState()

            is UiState.Error -> ErrorState(
                kind = state.kind,
                canRetry = state.canRetry,
                onRetry = onRetry,
            )

            is UiState.Success ->
                if (state.data.isEmpty()) {
                    EmptyState(stringResource(R.string.state_empty_saves))
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.data, key = { "${it.gameId}-${it.slot}" }) { save ->
                            SaveCard(save)
                        }
                    }
                }
        }
    }

    // Rendered last so it sits above whatever is behind it. A conflict is
    // modal on purpose: continuing to play against an unsynced save would
    // widen the divergence the player has to reconcile.
    conflict?.let {
        ConflictDialog(
            prompt = it,
            onKeepLocal = onKeepLocal,
            onKeepServer = onKeepServer,
            onDismiss = onDismissConflict,
        )
    }
}

@Composable
private fun SaveCard(save: CloudSave) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Slot ${save.slot}", style = MaterialTheme.typography.titleLarge)
                // The version is surfaced rather than hidden. It is part of
                // the API contract the player indirectly depends on, and
                // showing it makes a conflict comprehensible when one occurs.
                Text(
                    "v${save.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "${save.payloadSizeBytes / 1024} KB · updated ${save.updatedAt}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            save.deviceId?.let {
                Text(
                    "Last written by $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The conflict choice.
 *
 * Two explicit options and no default. The server cannot merge opaque save
 * bytes and neither can this dialog, so the only honest thing to do is ask.
 *
 * Silently picking one is exactly the last-write-wins behaviour the whole
 * optimistic-concurrency design exists to avoid.
 */
@Composable
private fun ConflictDialog(
    prompt: ConflictPrompt,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_body))
                Text(
                    "This device: ${prompt.localPayload.size / 1024} KB\n" +
                        "Other device: version ${prompt.serverVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepLocal) {
                Text(stringResource(R.string.conflict_keep_local))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepServer) {
                Text(stringResource(R.string.conflict_keep_server))
            }
        },
    )
}
