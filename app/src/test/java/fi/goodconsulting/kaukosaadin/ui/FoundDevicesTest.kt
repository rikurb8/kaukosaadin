package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.runtime.Composable
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundDevicesTest {
    private class Fake(
        override val kind: DeviceKind,
        override val name: String,
        override val host: String,
        override val detail: String? = null,
    ) : Candidate {
        @Composable
        override fun Pairing(host: PairingHost) = Unit
    }

    private val order = listOf(DeviceKind.AppleTv, DeviceKind.Lg, DeviceKind.Hue)
    private val lounge = Fake(DeviceKind.AppleTv, "Lounge", "10.0.0.2")
    private val bedroom = Fake(DeviceKind.AppleTv, "Bedroom", "10.0.0.3")
    private val oled = Fake(DeviceKind.Lg, "OLED65", "10.0.0.4")
    private val bridge = Fake(DeviceKind.Hue, "Hue Bridge", "10.0.0.5", detail = "BSB002")

    private fun found(vararg candidates: Candidate) = ScanOutcome.Found(candidates.toList())

    private fun done(
        appleTv: ScanOutcome = found(),
        lg: ScanOutcome = found(),
        hue: ScanOutcome = found(),
    ) = mapOf(DeviceKind.AppleTv to appleTv, DeviceKind.Lg to lg, DeviceKind.Hue to hue)

    @Test fun newDevicesComeFirstThenByKindOrderAndName() {
        val saved = listOf(SavedDevice("a", DeviceKind.AppleTv, "Lounge", "10.0.0.2"))
        val view = mergeScan(done(found(lounge, bedroom), found(oled), found(bridge)), saved, order)
        assertEquals(listOf(bedroom, oled, bridge, lounge), view.rows.map { it.candidate })
        assertEquals(listOf(false, false, false, true), view.rows.map { it.saved })
        assertEquals(3, view.newCount)
        assertEquals("Found 3 new devices", view.headline)
    }

    @Test fun savedMatchesNeedBothKindAndHost() {
        val saved = listOf(SavedDevice("l", DeviceKind.Lg, "OLED65", "10.0.0.2"))
        val view = mergeScan(done(found(lounge)), saved, order)
        assertFalse(view.rows.single().saved)
    }

    @Test fun theAddressShowsOnlyWhenNamesCollide() {
        val twin = Fake(DeviceKind.Lg, "lounge", "10.0.0.9")
        val view = mergeScan(done(found(lounge), found(twin, oled), found(bridge)), emptyList(), order)
        val subtitles = view.rows.associate { it.candidate.host to it.subtitle }
        assertEquals("Apple TV · 10.0.0.2", subtitles["10.0.0.2"])
        assertEquals("LG TV · 10.0.0.9", subtitles["10.0.0.9"])
        assertEquals("LG TV", subtitles["10.0.0.4"])
        assertEquals("Hue Bridge · BSB002", subtitles["10.0.0.5"])
    }

    @Test fun aKindStillScanningListsWhatItHasFoundSoFar() {
        val view =
            mergeScan(
                mapOf(
                    DeviceKind.AppleTv to ScanOutcome.Scanning(listOf(lounge)),
                    DeviceKind.Lg to ScanOutcome.Scanning(emptyList()),
                ),
                emptyList(),
                order,
            )
        assertTrue(view.scanning)
        assertEquals("Looking for devices on your Wi-Fi…", view.headline)
        assertEquals(listOf(lounge), view.rows.map { it.candidate })
    }

    @Test fun headlinesForEmptyAndAlreadyAddedResults() {
        assertEquals("No devices found", mergeScan(done(), emptyList(), order).headline)
        assertEquals("Found 1 new device", mergeScan(done(found(lounge)), emptyList(), order).headline)
        val saved = listOf(SavedDevice("a", DeviceKind.AppleTv, "Lounge", "10.0.0.2"))
        assertEquals("No new devices found", mergeScan(done(found(lounge)), saved, order).headline)
    }

    @Test fun partialFailuresAreNamedAndTotalFailureBlamesTheNetwork() {
        val partial = mergeScan(done(lg = ScanOutcome.Failed, hue = ScanOutcome.Failed), emptyList(), order)
        assertEquals("Couldn't scan for LG TVs and Hue Bridges.", partial.failureNote)
        assertFalse(partial.scanning)

        val total = mergeScan(done(ScanOutcome.Failed, ScanOutcome.Failed, ScanOutcome.Failed), emptyList(), order)
        assertNull(total.failureNote)
        assertEquals("Couldn't scan your network. Check that you're connected to Wi-Fi.", total.headline)
    }
}
