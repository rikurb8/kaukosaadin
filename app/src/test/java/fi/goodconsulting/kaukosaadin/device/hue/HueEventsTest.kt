package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HueEventsTest {
    @Test fun anUpdateFrameDecodesOnAndBrightness() {
        val events = HueEvents.decode(UPDATE_FRAME)
        assertEquals(
            listOf(HueEvent(action = "update", resourceId = "light-1", resourceType = "light", on = true, brightness = 42.5)),
            events,
        )
    }

    @Test fun aDeleteFrameKeepsTheResourceIdentityAndNoState() {
        val events = HueEvents.decode("""[{"id":"e","type":"delete","creationtime":"2026-01-01T00:00:00Z","data":[{"id":"light-1","type":"light"}]}]""")
        assertEquals(
            listOf(HueEvent(action = "delete", resourceId = "light-1", resourceType = "light", on = null, brightness = null)),
            events,
        )
    }

    @Test fun anAddFrameIsDecoded() {
        val events = HueEvents.decode("""[{"id":"e","type":"add","creationtime":"t","data":[{"id":"gl-1","type":"grouped_light","on":{"on":false}}]}]""")
        assertEquals(listOf(HueEvent("add", "gl-1", "grouped_light", on = false, brightness = null)), events)
    }

    @Test fun oneEventWithSeveralResourcesBecomesSeveralChanges() {
        val events =
            HueEvents.decode(
                """[{"id":"e","type":"update","creationtime":"t","data":[
                    {"id":"light-1","type":"light","on":{"on":true}},
                    {"id":"light-2","type":"light","dimming":{"brightness":25.0}}
                ]}]""",
            )
        assertEquals(
            listOf(
                HueEvent("update", "light-1", "light", on = true, brightness = null),
                HueEvent("update", "light-2", "light", on = null, brightness = 25.0),
            ),
            events,
        )
    }

    @Test fun resourcesWithoutAnIdOrTypeAreDropped() {
        val events =
            HueEvents.decode(
                """[{"id":"e","type":"update","data":[{"type":"light"},{"id":"light-1"},"junk"]}]""",
            )
        assertTrue(events.isEmpty())
    }

    @Test fun malformedDataYieldsNoEvents() {
        assertTrue(HueEvents.decode("not json").isEmpty())
        assertTrue(HueEvents.decode("{}").isEmpty())
    }

    @Test fun anEmptyEventArrayYieldsNoEvents() {
        assertTrue(HueEvents.decode("[]").isEmpty())
    }

    private companion object {
        const val UPDATE_FRAME =
            """[{"id":"e","type":"update","creationtime":"2026-01-01T00:00:00Z","data":[
                {"id":"light-1","type":"light","on":{"on":true},"dimming":{"brightness":42.5}}
            ]}]"""
    }
}
