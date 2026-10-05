package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionCryptoTest {
    @Test fun pinnedReferenceVectorsAndRejectionPaths() {
        val vectors = checkNotNull(javaClass.classLoader!!.getResourceAsStream("companion-crypto-vectors.json"))
            .bufferedReader().use { it.readText() }
        assertEquals(6, CompanionCryptoCheck.run(vectors).size)
    }
}
