package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class HueCredentialsTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val storage = FakeStorage()
    private val credentials = HueCredentials(storage, key = { key })

    @Test fun pairingAndTrustSurviveARoundTrip() {
        assertTrue(credentials.savePairing("192.168.1.42", "app-key-1"))
        assertTrue(credentials.saveTrust("192.168.1.42", "abc123"))

        // A fresh store over the same bytes reads the same material, as it would after a restart.
        val reloaded = HueCredentials(storage, key = { key })
        assertEquals("192.168.1.42", reloaded.host)
        assertEquals("app-key-1", reloaded.applicationKey)
        assertEquals("abc123", reloaded.pin)
    }

    @Test fun theAppKeyIsStoredEncryptedNotInTheClear() {
        credentials.savePairing("192.168.1.42", "app-key-1")
        val stored = storage.get("pairing")!!
        assertFalse(stored.contains("app-key-1"))
        assertNotEquals("", stored)
    }

    @Test fun unreadableStoredMaterialReadsAsAbsent() {
        credentials.savePairing("192.168.1.42", "app-key-1")
        storage.put("pairing", "not a sealed payload")
        assertNull(HueCredentials(storage, key = { key }).applicationKey)
    }

    @Test fun forgetClearsEverythingKeptForTheBridge() {
        credentials.savePairing("192.168.1.42", "app-key-1")
        credentials.saveTrust("192.168.1.42", "abc123")
        assertTrue(credentials.forget())
        assertNull(credentials.host)
        assertNull(credentials.pin)
        assertNull(credentials.applicationKey)
        assertTrue(storage.values.isEmpty())
    }

    @Test fun saveTrustStoresTheHostEvenBeforePairing() {
        credentials.saveTrust("192.168.1.42", "abc123")
        assertEquals("192.168.1.42", HueCredentials(storage, key = { key }).host)
        assertNull(credentials.applicationKey)
    }

    private class FakeStorage : HueStorage {
        val values = mutableMapOf<String, String>()

        override fun get(name: String): String? = values[name]

        override fun put(
            name: String,
            value: String?,
        ): Boolean {
            if (value == null) values.remove(name) else values[name] = value
            return true
        }

        override fun clear(): Boolean {
            values.clear()
            return true
        }
    }
}
