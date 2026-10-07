package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuperRemoteBindingsTest {
    private val appleTv = SavedDevice("atv-1", DeviceKind.AppleTv, "Olohuone", "192.168.1.30")
    private val bridge = SavedDevice("hue-1", DeviceKind.Hue, "Bridge", "192.168.1.40")
    private val youtube = AppleTvApp("com.google.ios.youtube", "YouTube")
    private val areena = AppleTvApp("fi.yle.areena", "Yle Areena")
    private val configured =
        SuperRemoteBindings(
            appleTvDeviceId = appleTv.id,
            hueDeviceId = bridge.id,
            hueGroupedLightId = "grouped-1",
            hueGroupedLightName = "Living room",
            shortcuts = listOf(youtube, areena),
        )

    @Test fun bindingsRoundTrip() {
        assertEquals(configured, SuperRemoteBindings.decode(SuperRemoteBindings.encode(configured)))
        val unconfigured = SuperRemoteBindings()
        assertEquals(unconfigured, SuperRemoteBindings.decode(SuperRemoteBindings.encode(unconfigured)))
    }

    @Test fun storedBindingsKeepTheKnownKeys() {
        // This is the shape an install keeps; renaming a key would drop the operator's choices.
        val stored = JSONObject(SuperRemoteBindings.encode(configured))
        assertEquals("atv-1", stored.getString("appleTvDeviceId"))
        assertEquals("hue-1", stored.getString("hueDeviceId"))
        assertEquals("grouped-1", stored.getString("hueGroupedLightId"))
        assertEquals("Living room", stored.getString("hueGroupedLightName"))
        val shortcut = stored.getJSONArray("shortcuts").getJSONObject(0)
        assertEquals("com.google.ios.youtube", shortcut.getString("bundleId"))
        assertEquals("YouTube", shortcut.getString("name"))
    }

    @Test fun handWrittenStorageDecodes() {
        val stored =
            """{"appleTvDeviceId":"atv-9","hueDeviceId":"hue-9","hueGroupedLightId":"grouped-9",""" +
                """"hueGroupedLightName":"Kitchen","shortcuts":[{"bundleId":"fi.yle.areena","name":"Yle Areena"}]}"""
        assertEquals(
            SuperRemoteBindings("atv-9", "hue-9", "grouped-9", "Kitchen", listOf(areena)),
            SuperRemoteBindings.decode(stored),
        )
    }

    @Test fun unreadableStorageDecodesToNotConfigured() {
        val unconfigured = SuperRemoteBindings()
        assertEquals(unconfigured, SuperRemoteBindings.decode(null))
        assertEquals(unconfigured, SuperRemoteBindings.decode("not json"))
        assertEquals(unconfigured, SuperRemoteBindings.decode("[]"))
        assertEquals(unconfigured, SuperRemoteBindings.decode("""{"hueDeviceId":null,"hueGroupedLightName":null}"""))
        assertEquals(unconfigured, SuperRemoteBindings.decode("""{"shortcuts":"junk"}"""))
        assertEquals(unconfigured, SuperRemoteBindings.decode("""{"shortcuts":[42,"junk",null]}"""))
        // A shortcut without a bundle id is dropped: there would be no app to launch.
        assertEquals(
            listOf(youtube),
            SuperRemoteBindings
                .decode("""{"shortcuts":[{"name":"YouTube"},{"bundleId":"com.google.ios.youtube","name":"YouTube"},"junk"]}""")
                .shortcuts,
        )
        // A shortcut with no stored name still carries the bundle id it would launch.
        assertEquals(
            listOf(AppleTvApp(youtube.bundleId, youtube.bundleId)),
            SuperRemoteBindings.decode("""{"shortcuts":[{"bundleId":"com.google.ios.youtube"}]}""").shortcuts,
        )
    }

    @Test fun theBoundDevicesAndTargetResolve() {
        val targets = configured.resolve(listOf(appleTv, bridge))
        assertEquals(appleTv, targets.appleTv)
        assertEquals(bridge, targets.lighting?.bridge)
        assertEquals(SuperRemoteGroupedLight("grouped-1", "Living room"), targets.lighting?.target)
    }

    @Test fun aForgottenOrWrongKindDeviceResolvesToMissing() {
        // The bound Apple TV was forgotten.
        assertNull(configured.resolve(listOf(bridge)).appleTv)
        // "atv-1" now names an LG TV: the section must reselect, never drive it.
        val changedKind = SavedDevice(appleTv.id, DeviceKind.Lg, "Living Room", "192.168.1.20")
        assertNull(configured.resolve(listOf(changedKind, bridge)).appleTv)
    }

    @Test fun resolutionNeverFallsBackToAnotherSavedDevice() {
        val otherAppleTv = SavedDevice("atv-2", DeviceKind.AppleTv, "Bedroom", "192.168.1.31")
        val otherBridge = SavedDevice("hue-2", DeviceKind.Hue, "Upstairs", "192.168.1.41")
        val targets = configured.resolve(listOf(otherAppleTv, otherBridge))
        assertNull(targets.appleTv)
        assertNull(targets.lighting)
    }

    @Test fun aMissingBridgeOrTargetResolvesToMissing() {
        // The bound bridge was forgotten, or is no longer saved as a bridge.
        assertNull(configured.resolve(listOf(appleTv)).lighting)
        val changedKind = SavedDevice(bridge.id, DeviceKind.AppleTv, "Olohuone", "192.168.1.30")
        assertNull(configured.resolve(listOf(appleTv, changedKind)).lighting)
        // No room or zone was chosen yet, so there is no grouped light to command.
        assertNull(configured.copy(hueGroupedLightId = null).resolve(listOf(appleTv, bridge)).lighting)
        assertNull(configured.copy(hueGroupedLightId = "  ").resolve(listOf(appleTv, bridge)).lighting)
    }

    @Test fun aTargetWithoutAStoredNameIsShownByItsId() {
        val devices = listOf(appleTv, bridge)
        val blank = configured.copy(hueGroupedLightName = "  ").resolve(devices)
        assertEquals(SuperRemoteGroupedLight("grouped-1", "grouped-1"), blank.lighting?.target)
        val absent = configured.copy(hueGroupedLightName = null).resolve(devices)
        assertEquals(SuperRemoteGroupedLight("grouped-1", "grouped-1"), absent.lighting?.target)
    }
}
