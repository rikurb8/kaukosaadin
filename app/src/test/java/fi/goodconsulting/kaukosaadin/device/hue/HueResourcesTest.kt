package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HueResourcesTest {
    @Test fun aLightDecodesItsIdNameOnAndBrightness() {
        val lights = HueResources.lights(envelope(LIGHTS))
        assertEquals(
            listOf(
                HueLight("light-1", "Ceiling", on = true, brightness = 42.5),
                HueLight("light-2", "Desk", on = false, brightness = 100.0),
            ),
            lights,
        )
    }

    @Test fun aLightWithoutMetadataFallsBackToItsIdAndReportsNoBrightness() {
        val lights = HueResources.lights(envelope("""{"errors":[],"data":[{"id":"light-9","type":"light","on":{"on":false}}]}"""))
        assertEquals(listOf(HueLight("light-9", "light-9", on = false, brightness = null)), lights)
    }

    @Test fun onlyMatchingTypedEntriesWithAnIdAreKept() {
        val body =
            """
            {"errors":[],"data":[
              {"id":"light-1","type":"light","on":{"on":true}},
              {"id":"","type":"light","on":{"on":true}},
              {"id":"room-1","type":"room","metadata":{"name":"Hall"}},
              "not an object"
            ]}
            """.trimIndent()
        val lights = HueResources.lights(envelope(body))
        assertEquals(listOf(HueLight("light-1", "light-1", on = true, brightness = null)), lights)
    }

    @Test fun aRoomFindsItsGroupedLightServiceAmongItsServices() {
        val rooms = HueResources.rooms(envelope(ROOMS))
        assertEquals(
            listOf(
                HueRoom("room-1", "Living room", groupedLightId = "grouped-2"),
                HueRoom("room-2", "Hall", groupedLightId = null),
            ),
            rooms,
        )
    }

    @Test fun aGroupedLightDecodesItsIdOnAndBrightness() {
        val groups = HueResources.groupedLights(envelope(GROUPS))
        assertEquals(
            listOf(
                HueGroupedLight("grouped-2", on = false, brightness = 10.0),
                HueGroupedLight("grouped-3", on = true, brightness = null),
            ),
            groups,
        )
    }

    @Test fun theEnvelopeKeepsErrorTypesAndNotTheirDescriptions() {
        val parsed = HueEnvelope.parse("""{"errors":[{"description":"unauthorized","type":1},{"description":"again","type":3}],"data":[]}""")
        assertEquals(listOf(1, 3), parsed?.errorTypes)
    }

    @Test fun anEmptyErrorsArrayIsSuccess() {
        assertEquals(emptyList<Int>(), HueEnvelope.parse("""{"errors":[],"data":[]}""")?.errorTypes)
    }

    @Test fun aNonEnvelopeBodyParsesToNull() {
        assertNull(HueEnvelope.parse("not json"))
        assertNull(HueEnvelope.parse("[]"))
        assertNull(HueEnvelope.parse(""))
    }

    @Test fun missingDataDecodesToNoResources() {
        assertTrue(HueResources.lights(envelope("""{"errors":[]}""")).isEmpty())
    }

    private fun envelope(data: String): HueEnvelope = HueEnvelope.parse(data) ?: error("test payload did not parse")

    private companion object {
        const val LIGHTS =
            """{"errors":[],"data":[
                {"id":"light-1","type":"light","metadata":{"name":"Ceiling"},"on":{"on":true},"dimming":{"brightness":42.5}},
                {"id":"light-2","type":"light","metadata":{"name":"Desk"},"on":{"on":false},"dimming":{"brightness":100.0}}
            ]}"""

        const val ROOMS =
            """{"errors":[],"data":[
                {"id":"room-1","type":"room","metadata":{"name":"Living room"},
                 "services":[{"rid":"other-1","rtype":"something_else"},{"rid":"grouped-2","rtype":"grouped_light"}]},
                {"id":"room-2","type":"room","metadata":{"name":"Hall"},"services":[]}
            ]}"""

        const val GROUPS =
            """{"errors":[],"data":[
                {"id":"grouped-2","type":"grouped_light","on":{"on":false},"dimming":{"brightness":10.0}},
                {"id":"grouped-3","type":"grouped_light","on":{"on":true}}
            ]}"""
    }
}
