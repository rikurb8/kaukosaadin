package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIntegrationsTest {
    @Test fun everyKindHasExactlyOneRegisteredIntegration() {
        DeviceKind.entries.forEach { kind ->
            assertEquals(kind, DeviceIntegrations.of(kind).kind)
        }
        assertEquals(
            DeviceKind.entries.size,
            DeviceIntegrations.all
                .map { it.kind }
                .distinct()
                .size,
        )
    }

    @Test fun onlyLgTvsAndBridgesAreAddedByAddress() {
        assertEquals(
            listOf(DeviceKind.Lg, DeviceKind.Hue),
            DeviceIntegrations.all.filter { it.addsByAddress }.map { it.kind },
        )
        assertNull(AppleTvIntegration.candidateAt("192.168.1.20"))
    }

    @Test fun aTypedAddressMustBeALanIpv4() {
        listOf(LgIntegration, HueIntegration).forEach { integration ->
            val candidate = integration.candidateAt("192.168.1.20")
            assertEquals(integration.kind, candidate?.kind)
            assertEquals("192.168.1.20", candidate?.host)
            assertEquals(integration.kind.label, candidate?.name)
            listOf("", "tv.local", "http://192.168.1.20", "192.168.1", "127.0.0.1").forEach {
                assertNull("$it for ${integration.kind}", integration.candidateAt(it))
            }
        }
    }
}
