package com.gamehub.android.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamehub.android.BuildConfig

/**
 * Settings.
 *
 * Deliberately sparse. The theme follows the system, including dynamic
 * colour, so an in-app theme toggle would fight the platform rather than help.
 * Notification and account settings belong here once those features exist;
 * adding rows for them now would be a menu of things that do nothing.
 *
 * What is here is the diagnostic information a support conversation actually
 * needs.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("About", style = MaterialTheme.typography.titleLarge)

        SettingRow("Version", BuildConfig.VERSION_NAME)
        SettingRow("Build", BuildConfig.BUILD_TYPE)
        // Surfaced because "which server is this build talking to?" is the
        // first question when a tester reports something odd.
        SettingRow("Server", BuildConfig.API_BASE_URL)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text("Data", style = MaterialTheme.typography.titleLarge)
        Text(
            "Game details are cached on this device so browsing works offline. " +
                "Leaderboards, matches and cloud saves are always fetched live, " +
                "because a stale value there would be misleading rather than useful.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
