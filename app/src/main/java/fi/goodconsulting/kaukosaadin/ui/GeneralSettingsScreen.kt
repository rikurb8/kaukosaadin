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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Standard
    }
}

@Composable
internal fun GeneralSettingsScreen(
    padding: PaddingValues,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    layout: AppLayout,
    onLayout: (AppLayout) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
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
    }
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
