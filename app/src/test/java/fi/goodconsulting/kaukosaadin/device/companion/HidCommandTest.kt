package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/** Codes are pinned to pyatv `protocols/companion/api.py` HidCommand; a wrong code is a no-op press. */
class HidCommandTest {
    @Test fun codesMatchPinnedPyatv() {
        val pinned =
            mapOf(
                "Up" to 1,
                "Down" to 2,
                "Left" to 3,
                "Right" to 4,
                "Menu" to 5,
                "Select" to 6,
                "Home" to 7,
                "Sleep" to 12,
                "PlayPause" to 14,
            )
        assertEquals(pinned, HidCommand.entries.associate { it.name to it.code })
    }
}
