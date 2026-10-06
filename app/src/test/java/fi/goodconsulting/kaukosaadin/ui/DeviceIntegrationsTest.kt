package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import org.junit.Assert.assertEquals
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
}
