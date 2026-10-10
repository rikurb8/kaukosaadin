package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice

private val devices =
    listOf(
        SavedDevice("preview-apple", DeviceKind.AppleTv, "Living room Apple TV", "192.0.2.10"),
        SavedDevice("preview-lg", DeviceKind.Lg, "Living room LG", "192.0.2.11"),
        SavedDevice("preview-hue", DeviceKind.Hue, "Living room Hue Bridge", "192.0.2.12"),
    )

// Fixtures are for rendering only: no discovery, pairing, credentials or network calls.
private class PreviewCandidate(
    private val device: SavedDevice,
) : Candidate {
    override val kind = device.kind
    override val name = device.name
    override val host = device.host
    override val detail = if (kind == DeviceKind.Hue) "BSB002" else null

    @Composable
    override fun Pairing(host: PairingHost) = Unit
}

private val candidates = devices.map(::PreviewCandidate)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, shapes = AppShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
private fun ScanScreenshot(
    outcomes: Map<DeviceKind, ScanOutcome>,
    saved: List<SavedDevice> = emptyList(),
) {
    PreviewFrame {
        AddDeviceContent(PaddingValues(0.dp), mergeScan(outcomes, saved), {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun ScanningScreenshot() {
    ScanScreenshot(
        DeviceKind.entries.associateWith { kind ->
            ScanOutcome.Scanning(candidates.filter { it.kind == kind && kind == DeviceKind.AppleTv })
        },
    )
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun FoundDevicesScreenshot() {
    ScanScreenshot(
        DeviceKind.entries.associateWith { kind -> ScanOutcome.Found(candidates.filter { it.kind == kind }) },
        saved = listOf(devices.first()),
    )
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun NoDevicesScreenshot() {
    ScanScreenshot(DeviceKind.entries.associateWith { ScanOutcome.Found(emptyList()) })
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun ScanFailureScreenshot() {
    ScanScreenshot(DeviceKind.entries.associateWith { ScanOutcome.Failed })
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun SavedDevicesScreenshot() {
    val context = LocalContext.current
    val store = remember { DeviceStore(context) }
    PreviewFrame {
        DevicesScreen(PaddingValues(0.dp), store, devices, devices.first(), {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun EmptyRemoteScreenshot() {
    PreviewFrame { EmptyRemoteScreen(PaddingValues(0.dp), {}, {}) }
}

@PreviewTest
@Preview(widthDp = 320, heightDp = 568, fontScale = 1.3f)
@Composable
fun CompactWelcomeScreenshot() {
    PreviewFrame { EmptyRemoteScreen(PaddingValues(0.dp), {}, {}) }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun DarkWelcomeScreenshot() {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, shapes = AppShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            EmptyRemoteScreen(PaddingValues(0.dp), {}, {})
        }
    }
}

// Pairing captures show the real step content, without a modal window or a device connection.
@Composable
private fun PairingFrame(content: @Composable () -> Unit) {
    PreviewFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 600)
@Composable
fun AppleTvPinScreenshot() {
    PairingFrame { AppleTvPinStep(devices.first().name, "12", null, StepAction("Cancel") {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 320, heightDp = 480, fontScale = 1.3f)
@Composable
fun CompactPinScreenshot() {
    PairingFrame { AppleTvPinStep(devices.first().name, "12", null, StepAction("Cancel") {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 600)
@Composable
fun LgConfirmScreenshot() {
    PairingFrame { LgConfirmStep(devices[1].name, devices[1].name, "preview fingerprint", StepAction("Cancel") {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 600)
@Composable
fun HueLinkButtonScreenshot() {
    PairingFrame { PressLinkButton(devices[2].name, StepAction("Cancel") {}) }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 1100)
@Composable
fun GeneralSettingsScreenshot() {
    PreviewFrame {
        GeneralSettingsScreen(PaddingValues(0.dp), AppTheme.Classic, {}, AppLayout.Standard, {}, {}, {})
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun ClearAllDataScreenshot() {
    PreviewFrame { ClearAllDataDialog({}, {}) }
}
