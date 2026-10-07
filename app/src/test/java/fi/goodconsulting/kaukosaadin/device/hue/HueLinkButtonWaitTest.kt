package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HueLinkButtonWaitTest {
    private val waiting = HueClient.Result(false, "Waiting", waitingForLinkButton = true)
    private val paired = HueClient.Result(true, "Paired")
    private val rejected = HueClient.Result(false, "Unauthorized")

    private fun wait(results: List<HueClient.Result>): Pair<HueClient.Result, Pair<Int, List<Long>>> {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val result =
            runBlocking {
                awaitLinkButton(attempts = 15, intervalMs = 2_000, pause = { pauses += it }) {
                    results[minOf(calls++, results.lastIndex)]
                }
            }
        return result to (calls to pauses)
    }

    @Test fun pairsAsSoonAsTheButtonIsPressed() {
        val (result, stats) = wait(listOf(waiting, waiting, paired))
        assertEquals(paired, result)
        assertEquals(3, stats.first)
        assertEquals(listOf(2_000L, 2_000L), stats.second)
    }

    @Test fun anyOtherFailureStopsTheWait() {
        val (result, stats) = wait(listOf(waiting, rejected, paired))
        assertEquals(rejected, result)
        assertEquals(2, stats.first)
    }

    @Test fun givesUpAfterTheLastAttempt() {
        val (result, stats) = wait(listOf(waiting))
        assertTrue(result.waitingForLinkButton)
        assertEquals(15, stats.first)
        assertEquals(14, stats.second.size)
    }

    @Test fun theCountdownCoversEveryAttempt() {
        assertEquals(30, LINK_BUTTON_WAIT_SECONDS)
    }
}
