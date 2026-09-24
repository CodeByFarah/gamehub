package com.gamehub.android.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamehub.android.R
import com.gamehub.android.domain.ErrorKind

/**
 * The three states every loading screen needs, in one place.
 *
 * Written once because a project where each screen invents its own empty state
 * ends up with five different tones of voice and five different retry
 * behaviours.
 */

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Announced to screen readers, which otherwise hear nothing at
            // all while the screen is loading.
            .semantics { contentDescription = "Loading" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * @param message names what is missing and what to do about it. Never
 *        "No data": that tells the player nothing they can act on.
 */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Error state, with a retry offered only where retrying can actually help.
 *
 * Offering retry on a 401 or a client error trains people to tap a button that
 * never works.
 */
@Composable
fun ErrorState(
    kind: ErrorKind,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val message = when (kind) {
        ErrorKind.OFFLINE -> stringResource(R.string.error_offline)
        ErrorKind.SERVER -> stringResource(R.string.error_server)
        ErrorKind.UNAUTHORIZED -> stringResource(R.string.error_unauthorized)
        ErrorKind.CLIENT, ErrorKind.UNKNOWN -> stringResource(R.string.error_generic)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        when {
            kind == ErrorKind.UNAUTHORIZED ->
                TextButton(onClick = onSignIn) {
                    Text(stringResource(R.string.action_sign_in))
                }

            canRetry ->
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
        }
    }
}

/**
 * Shown above stale content, not instead of it.
 *
 * A failed refresh with usable cached data is not an error screen. Replacing
 * real content the player can still read with an error page is the wrong
 * trade, so this is a banner.
 */
@Composable
fun StaleBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.error_offline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Marks an AI response that came from the deterministic fallback.
 *
 * The API reports `degraded` honestly and the UI says so. Hiding it would mean
 * presenting keyword matching as if a model had understood the request.
 */
@Composable
fun DegradedAiBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.ai_degraded),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
