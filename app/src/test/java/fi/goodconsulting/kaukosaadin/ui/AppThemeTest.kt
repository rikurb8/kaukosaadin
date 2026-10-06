package fi.goodconsulting.kaukosaadin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    @Test fun savedThemesRoundTripAndUnknownValuesUseClassic() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromId(it.id)) }
        assertEquals(AppTheme.Classic, AppTheme.fromId(null))
        assertEquals(AppTheme.Classic, AppTheme.fromId("removed_theme"))
    }

    @Test fun savedLayoutsRoundTripAndUnknownValuesUseStandard() {
        AppLayout.entries.forEach { assertEquals(it, AppLayout.fromId(it.id)) }
        assertEquals(AppLayout.Standard, AppLayout.fromId(null))
        assertEquals(AppLayout.Standard, AppLayout.fromId("removed_layout"))
    }

    @Test fun theSuperRemoteLayoutKeepsItsStoredId() {
        // "super_remote" is the id an install already has under "layout"; renaming it drops the layout.
        assertEquals("super_remote", AppLayout.SuperRemote.id)
        assertEquals(AppLayout.SuperRemote, AppLayout.fromId("super_remote"))
    }

    @Test fun addingTheSuperRemoteLayoutKeepsTheOtherLayoutsAndBothThemesOffered() {
        // The settings screen offers every entry, and these ids are what an install already stores.
        assertEquals(listOf("standard", "debug", "super_remote"), AppLayout.entries.map { it.id })
        assertEquals(listOf("classic", "hacker_man"), AppTheme.entries.map { it.id })
    }
}
