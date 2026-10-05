package fi.goodconsulting.kaukosaadin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FitScaleTest {
    @Test fun contentWithinBoundsIsNotScaled() {
        assertEquals(1f, fitScale(contentHeight = 700, availableHeight = 700), 0f)
        assertEquals(1f, fitScale(contentHeight = 500, availableHeight = 700), 0f)
    }

    @Test fun tallerContentShrinksToFitExactly() {
        assertEquals(2340f / 2690f, fitScale(contentHeight = 2690, availableHeight = 2340), 1e-6f)
    }

    @Test fun shrinkingStopsAtTheFloorSoTheRestScrolls() {
        assertEquals(MinFitScale, fitScale(contentHeight = 1400, availableHeight = 700), 0f)
        assertEquals(MinFitScale, fitScale(contentHeight = 100_000, availableHeight = 700), 0f)
    }
}
