package com.gamehub.android.ui.screen.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gamehub.android.R
import com.gamehub.android.ui.component.DegradedAiBadge

@Composable
fun AssistantRoute(
    gameId: String? = null,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()

    AssistantScreen(
        messages = messages,
        thinking = thinking,
        onAsk = { viewModel.ask(it, gameId) },
    )
}

@Composable
fun AssistantScreen(
    messages: List<AssistantMessage>,
    thinking: Boolean,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scrolls to the newest message as it arrives. Without this the answer
    // lands below the fold and looks like nothing happened.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (thinking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                item { Suggestions(onAsk) }
            }

            // Indexed key: messages are append-only and two identical
            // questions are legitimate, so content alone is not unique.
            itemsIndexed(messages) { index, message ->
                MessageBubble(message)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about a game") },
                // Length-capped here as well as server-side, so an oversized
                // prompt is prevented rather than rejected after the round
                // trip.
                supportingText = { Text("${draft.length}/500") },
                isError = draft.length > MAX_PROMPT,
                maxLines = 3,
            )

            IconButton(
                onClick = {
                    onAsk(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && draft.length <= MAX_PROMPT && !thinking,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/**
 * Starter prompts.
 *
 * An empty chat box is a hard prompt to answer. These are the shapes the
 * backend handles well, including in fallback mode, so a first attempt
 * succeeds whether or not a model is configured.
 */
@Composable
private fun Suggestions(onAsk: (String) -> Unit) {
    Column {
        Text(
            "Try asking",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        listOf(
            "Which games can I finish in 20 minutes?",
            "What is a good competitive game for two players?",
            "Something relaxing to play alone",
        ).forEach { suggestion ->
            TextButton(onClick = { onAsk(suggestion) }) { Text(suggestion) }
        }
    }
}

@Composable
private fun MessageBubble(message: AssistantMessage) {
    when (message) {
        is AssistantMessage.FromPlayer -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(message.text, Modifier.padding(12.dp))
            }
        }

        is AssistantMessage.FromAssistant -> Column {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(message.text, Modifier.padding(12.dp))
            }

            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Both flags surfaced. The API reports them honestly and the
                // UI says so, rather than presenting keyword matching as if a
                // model had understood the question.
                if (message.degraded) {
                    DegradedAiBadge()
                }
                if (!message.grounded) {
                    Text(
                        stringResource(R.string.ai_ungrounded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (message.referencedGames.isNotEmpty()) {
                Text(
                    "Mentioned: " + message.referencedGames.joinToString { it.title },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        is AssistantMessage.Failed -> Text(
            // Reaching here means the provider and the fallback both failed,
            // which is a real outage rather than routine flakiness.
            text = stringResource(R.string.error_server),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private const val MAX_PROMPT = 500
