package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperRemoteSetupTest {
    private val youtube = AppleTvApp("com.google.ios.youtube", "YouTube")
    private val areena = AppleTvApp("fi.yle.areena", "Yle Areena")
    private val netflix = AppleTvApp("com.netflix.Netflix", "Netflix")

    @Test fun preferredShortcutsAreYouTubeAndAreenaOnlyWhenTheTvReportsThem() {
        assertEquals(listOf(youtube, areena), preferredShortcuts(listOf(netflix, areena, youtube)))
        assertEquals(listOf(youtube), preferredShortcuts(listOf(youtube, netflix)))
        // The bundle id is the TV's own; setup never invents a YouTube or Yle Areena identifier.
        assertEquals(listOf(AppleTvApp("tv.youtube", "YouTube")), preferredShortcuts(listOf(AppleTvApp("tv.youtube", "YouTube"))))
        assertEquals(emptyList<AppleTvApp>(), preferredShortcuts(listOf(netflix)))
        assertEquals(emptyList<AppleTvApp>(), preferredShortcuts(emptyList()))
    }

    @Test fun thePreferredNamesMatchRegardlessOfCase() {
        val lower = AppleTvApp("app.youtube", "youtube")
        assertEquals(listOf(lower), preferredShortcuts(listOf(lower)))
    }

    @Test fun aMissingReportLeavesTheStoredShortcutsAlone() {
        val stored = listOf(netflix)
        assertEquals(stored, shortcutsFor(emptyList(), stored))
        // A report that does not carry the preferred apps keeps the operator's current choice too.
        assertEquals(stored, shortcutsFor(listOf(youtube), stored))
    }

    @Test fun anEmptySelectionAdoptsOnlyTheReportedPreferredApps() {
        assertEquals(listOf(youtube, areena), shortcutsFor(listOf(netflix, areena, youtube), emptyList()))
        assertEquals(emptyList<AppleTvApp>(), shortcutsFor(listOf(netflix), emptyList()))
        assertEquals(emptyList<AppleTvApp>(), shortcutsFor(emptyList(), emptyList()))
    }

    @Test fun aChosenDeviceTargetAndBothShortcutsSurviveAFreshStore() {
        val store = MemoryStore()
        val setup = SuperRemoteSetup(store::read, store::write)
        assertTrue(setup.chooseAppleTv("atv-1"))
        assertTrue(setup.chooseLightTarget("hue-1", "grouped-9", "Olohuone"))
        assertTrue(setup.chooseShortcuts(listOf(youtube, areena)))

        // A fresh store reading the same bytes sees the choices, as it would after a restart.
        val freshStore = MemoryStore(store.stored)
        val fresh = SuperRemoteSetup(freshStore::read, freshStore::write)
        assertEquals("atv-1", fresh.bindings.appleTvDeviceId)
        assertEquals("hue-1", fresh.bindings.hueDeviceId)
        assertEquals("grouped-9", fresh.bindings.hueTargetId)
        assertEquals("Olohuone", fresh.bindings.hueTargetName)
        assertEquals(listOf(youtube, areena), fresh.bindings.shortcuts)
    }

    @Test fun changingTheBridgeDropsTheOldBridgesTarget() {
        val store = MemoryStore()
        val setup = SuperRemoteSetup(store::read, store::write)
        setup.chooseLightTarget("hue-1", "grouped-9", "Olohuone")
        // Re-choosing the same bridge keeps the room or zone it has.
        setup.chooseBridge("hue-1")
        assertEquals("grouped-9", setup.bindings.hueTargetId)
        // Another bridge cannot command the old bridge's grouped light, so its target is dropped.
        setup.chooseBridge("hue-2")
        assertEquals("hue-2", setup.bindings.hueDeviceId)
        assertNull(setup.bindings.hueTargetId)
        assertNull(setup.bindings.hueTargetName)
    }

    @Test fun choosingOneSectionLeavesTheOthersUntouched() {
        val store = MemoryStore()
        val setup = SuperRemoteSetup(store::read, store::write)
        setup.chooseShortcuts(listOf(youtube))
        setup.chooseAppleTv("atv-1")
        assertEquals(listOf(youtube), setup.bindings.shortcuts)
        assertNull(setup.bindings.hueDeviceId)
    }

    @Test fun theSetupWritesOnlyTheSuperRemotesOwnBindings() {
        // The device picker's list and selection live in DeviceStore's own "devices" storage; the setup's
        // sole write channel is the Super remote's bindings, which have no key for the picker's selection.
        val written = mutableListOf<SuperRemoteBindings>()
        val setup =
            SuperRemoteSetup({ SuperRemoteBindings() }) { bindings ->
                written += bindings
                true
            }
        setup.chooseAppleTv("atv-1")
        setup.chooseLightTarget("hue-1", "grouped-9", "Olohuone")
        setup.chooseShortcuts(listOf(youtube))
        val keys = written.flatMap { JSONObject(SuperRemoteBindings.encode(it)).keys().asSequence() }.toSet()
        assertEquals(setOf("appleTvDeviceId", "hueDeviceId", "hueTargetId", "hueTargetName", "shortcuts"), keys)
    }

    /** The store's prefs file, modelled as the one string it keeps, so a round-trip needs no Android device. */
    private class MemoryStore(
        var stored: String? = null,
    ) {
        fun read(): SuperRemoteBindings = SuperRemoteBindings.decode(stored)

        fun write(bindings: SuperRemoteBindings): Boolean {
            stored = SuperRemoteBindings.encode(bindings)
            return true
        }
    }
}
