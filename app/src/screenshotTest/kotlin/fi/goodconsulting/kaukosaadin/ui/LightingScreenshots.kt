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
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight

// A rendering-only house: a lit living room, a dim bedroom, a dark kitchen, a zone and a loose lamp.
private fun light(
    id: String,
    name: String,
    on: Boolean,
    brightness: Double,
) = HueLight(id, name, on, brightness, ownerId = "device-$id")

private val house =
    LightingState(
        loading = false,
        lights =
            listOf(
                light("ceiling", "Ceiling", on = true, brightness = 78.0),
                light("sofa", "Sofa lamp", on = true, brightness = 40.0),
                light("shelf", "Shelf strip", on = false, brightness = 60.0),
                light("tv", "TV backlight", on = true, brightness = 92.0),
                light("bedside", "Bedside", on = true, brightness = 18.0),
                light("wardrobe", "Wardrobe", on = false, brightness = 50.0),
                light("island", "Island pendants", on = false, brightness = 100.0),
                light("hob", "Hob", on = false, brightness = 100.0),
                light("porch", "Porch", on = false, brightness = 70.0),
            ),
        rooms =
            listOf(
                HueGroup("living", "Living room", "g-living", setOf("device-ceiling", "device-sofa", "device-shelf", "device-tv")),
                HueGroup("bedroom", "Bedroom", "g-bedroom", setOf("device-bedside", "device-wardrobe")),
                HueGroup("kitchen", "Kitchen", "g-kitchen", setOf("device-island", "device-hob")),
            ),
        zones = listOf(HueGroup("movie", "Movie night", "g-movie", setOf("sofa", "tv"))),
        groupedLights =
            listOf(
                HueGroupedLight("g-living", on = true, brightness = 70.0),
                HueGroupedLight("g-bedroom", on = true, brightness = 18.0),
                HueGroupedLight("g-kitchen", on = false, brightness = 100.0),
                HueGroupedLight("g-movie", on = true, brightness = 66.0),
            ),
        favoriteGroups = setOf("living"),
        favoriteLights = setOf("sofa"),
    )

private val noActions = LightingActions({}, {}, {}, {}, { _, _ -> }, {})

@Composable
private fun LightingFrame(colors: ColorScheme) {
    val bridge = SavedDevice("preview-hue", DeviceKind.Hue, "Living room Hue Bridge", "192.0.2.12")
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes) {
        BridgeShell(
            remote =
                RemoteActions(
                    devices = listOf(bridge, SavedDevice("preview-apple", DeviceKind.AppleTv, "Living room Apple TV", "192.0.2.10")),
                    current = bridge,
                    layout = AppLayout.Standard,
                    onSelect = {},
                    onAddDevice = {},
                    onDevices = {},
                    onSettings = {},
                    onGeneralSettings = {},
                ),
            padding = PaddingValues(0.dp),
            ready = true,
            status = "4 OF 9 LIGHTS ON",
            txFlash = { 0f },
        ) {
            LightingList(house, noActions, initiallyOpen = setOf("living"))
        }
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 1500)
@Composable
fun LightingRoomsScreenshot() {
    LightingFrame(LightColors)
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 1500)
@Composable
fun LightingRoomsDarkScreenshot() {
    LightingFrame(DarkColors)
}
