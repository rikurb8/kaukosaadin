package fi.goodconsulting.kaukosaadin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedDevicesTest {
    private val lg = SavedDevice("lg-1", DeviceKind.Lg, "Living Room", "192.168.1.20")
    private val lg2 = SavedDevice("lg-2", DeviceKind.Lg, "Bedroom", "192.168.1.21")
    private val appleTv = SavedDevice("atv-1", DeviceKind.AppleTv, "Olohuone", "192.168.1.30")

    @Test fun listRoundTrips() {
        val devices = listOf(lg, appleTv, lg2)
        assertEquals(devices, DeviceStore.decode(DeviceStore.encode(devices)))
        assertEquals(emptyList<SavedDevice>(), DeviceStore.decode(DeviceStore.encode(emptyList())))
    }

    @Test fun lastUsedDeviceWins() {
        assertEquals(lg2, DeviceStore.current(listOf(lg, appleTv, lg2), "lg-2"))
    }

    @Test fun appleTvIsTheDefaultUntilOneIsChosen() {
        assertEquals(appleTv, DeviceStore.current(listOf(lg, appleTv), null))
        // A removed selection falls back the same way.
        assertEquals(appleTv, DeviceStore.current(listOf(lg, appleTv), "gone"))
    }

    @Test fun firstDeviceWithoutAnAppleTv() {
        assertEquals(lg, DeviceStore.current(listOf(lg, lg2), null))
        assertNull(DeviceStore.current(emptyList(), "lg-1"))
    }

    @Test fun unreadableStorageIsDroppedNotFatal() {
        assertEquals(emptyList<SavedDevice>(), DeviceStore.decode(null))
        assertEquals(emptyList<SavedDevice>(), DeviceStore.decode("not json"))
        assertEquals(emptyList<SavedDevice>(), DeviceStore.decode("{}"))
        val mixed =
            """[{"id":"x","kind":"Roku","name":"?"},{"kind":"Lg","name":"no id"},42,""" +
                """{"id":"lg-1","kind":"Lg","name":"Living Room","host":"192.168.1.20"}]"""
        assertEquals(listOf(lg), DeviceStore.decode(mixed))
    }

    @Test fun namesAreSafeAndFallBackToTheKind() {
        assertEquals("Living Room", DeviceStore.displayName("  Living Room  ", DeviceKind.Lg))
        assertEquals("LGTV", DeviceStore.displayName("LG\nTV\u0000", DeviceKind.Lg))
        assertEquals("LG TV", DeviceStore.displayName(" \n\t ", DeviceKind.Lg))
        assertEquals("Apple TV", DeviceStore.displayName("", DeviceKind.AppleTv))
        assertEquals(160, DeviceStore.displayName("x".repeat(200), DeviceKind.Lg).length)
        // Stored names pass through the same rules.
        assertEquals("Apple TV", DeviceStore.decode("""[{"id":"a","kind":"AppleTv","name":""}]""").single().name)
    }
}
