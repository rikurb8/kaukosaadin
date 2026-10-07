package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class AppTheme(
    val id: String,
    val label: String,
    val description: String,
) {
    Classic("classic", "Classic", "Cream and charcoal with an orange accent. Follows system light/dark mode."),
    HackerMan("hacker_man", "Hacker man", "Demo theme: phosphor green on black. Always dark."),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Classic
    }
}

/** How the remote is laid out; colors are chosen separately by [AppTheme]. */
internal enum class AppLayout(
    val id: String,
    val label: String,
    val description: String,
) {
    Standard("standard", "Standard", "The remote as designed: full casing, wheel and VFD display."),
    Debug("debug", "Debug", "Flat panels with a live status log, for development and troubleshooting."),
    SuperRemote(
        "super_remote",
        "Super remote",
        "App shortcuts, Apple TV controls and the room's or zone's lighting on one screen.",
    ),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Standard
    }
}

/**
 * App-wide settings: appearance, which applies to every device, and [onClearAllData], the reset
 * that returns the app to a fresh install. Reset is confirmed rather than toggled, so the
 * destructive half cannot be reached by a stray tap.
 */
@Composable
internal fun GeneralSettingsScreen(
    padding: PaddingValues,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    layout: AppLayout,
    onLayout: (AppLayout) -> Unit,
    onClearAllData: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmingReset by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("General settings", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        ChoiceGroup(AppTheme.entries, theme, { it.label }, { it.description }, onTheme)
        Text("Layout", style = MaterialTheme.typography.titleMedium)
        ChoiceGroup(AppLayout.entries, layout, { it.label }, { it.description }, onLayout)
        Text("Reset", style = MaterialTheme.typography.titleMedium)
        Text(
            "Clears everything the app keeps: every saved device and its pairing, the Super remote's " +
                "shortcuts and bindings, and these settings. The TVs and bridge have to be paired again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { confirmingReset = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Clear all app data")
        }
    }
    if (confirmingReset) {
        ClearAllDataDialog(onConfirm = onClearAllData, onDismiss = { confirmingReset = false })
    }
}

@Composable
internal fun ClearAllDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear all app data?") },
        text = {
            Text(
                "Every saved device is forgotten and its pairing is deleted, along with the Super " +
                    "remote's shortcuts and bindings and these settings. The app restarts with no " +
                    "devices added, like a fresh install.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Clear all app data")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Radio list for one setting; theme and layout share it. */
@Composable
private fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected == option, onClick = null)
                Column {
                    Text(label(option), style = MaterialTheme.typography.titleMedium)
                    Text(description(option), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
