package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionSkipSupport

// Rendering-only remotes: no client, session or network, just what each kind hands RemoteScreen.
private val appleTv = SavedDevice("preview-apple", DeviceKind.AppleTv, "Living room Apple TV", "192.0.2.10")
private val lgTv = SavedDevice("preview-lg", DeviceKind.Lg, "Living room LG", "192.0.2.11")

@Composable
private fun RemoteFrame(
    current: SavedDevice,
    status: String,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
) {
    MaterialTheme(colorScheme = LightColors) {
        RemoteScreen(
            contentPadding = PaddingValues(0.dp),
            devices = listOf(appleTv, lgTv),
            current = current,
            layout = AppLayout.Standard,
            ready = true,
            busy = false,
            powerEnabled = true,
            status = status,
            onSelect = {},
            onAddDevice = {},
            onDevices = {},
            onConnect = onConnect,
            onApps = onApps,
            onSettings = {},
            onGeneralSettings = {},
            onPower = {},
            onKey = { _, _ -> },
            skipSupport = CompanionSkipSupport(forward = true, backward = true),
            onSkip = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun AppleTvRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", onConnect = null, onApps = {})
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun LgRemoteScreenshot() {
    RemoteFrame(lgTv, "Connected.", onConnect = {}, onApps = null)
}
