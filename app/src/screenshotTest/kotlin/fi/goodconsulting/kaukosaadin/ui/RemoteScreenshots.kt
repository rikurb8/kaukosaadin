package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
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
    colors: ColorScheme = LightColors,
    ready: Boolean = true,
    busy: Boolean = false,
    layout: AppLayout = AppLayout.Standard,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes) {
        RemoteScreen(
            contentPadding = contentPadding,
            devices = listOf(appleTv, lgTv),
            current = current,
            layout = layout,
            ready = ready,
            busy = busy,
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

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun DarkRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", null, {}, colors = DarkColors)
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun HackerRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", null, {}, colors = HackerManColors)
}

@PreviewTest
@Preview(widthDp = 320, heightDp = 568, fontScale = 1.3f)
@Composable
fun CompactRemoteScreenshot() {
    RemoteFrame(
        appleTv.copy(name = "A very long living room Apple TV name"),
        "Connected.",
        null,
        {},
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
    )
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun DisconnectedRemoteScreenshot() {
    RemoteFrame(lgTv, "Unable to connect. Check that your TV is on and on the same Wi-Fi.", {}, null, ready = false)
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun BusyRemoteScreenshot() {
    RemoteFrame(appleTv, "Sending a command…", null, {}, busy = true)
}

@PreviewTest
@Preview(widthDp = 915, heightDp = 412)
@Composable
fun LandscapeRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", null, {})
}

@PreviewTest
@Preview(widthDp = 568, heightDp = 320)
@Composable
fun CompactLandscapeRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", null, {}, contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp))
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 1100)
@Composable
fun DebugRemoteScreenshot() {
    RemoteFrame(appleTv, "Connected.", null, {}, layout = AppLayout.Debug)
}
