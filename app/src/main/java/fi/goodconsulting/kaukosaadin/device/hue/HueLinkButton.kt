package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.delay

/** How long pairing waits for the link button: [LINK_BUTTON_ATTEMPTS] tries [LINK_BUTTON_INTERVAL_MS] apart. */
internal const val LINK_BUTTON_ATTEMPTS = 15

internal const val LINK_BUTTON_INTERVAL_MS = 2_000L

/** The whole wait in seconds, for the countdown the operator sees. */
internal const val LINK_BUTTON_WAIT_SECONDS = (LINK_BUTTON_ATTEMPTS * LINK_BUTTON_INTERVAL_MS / 1_000L).toInt()

/**
 * Repeats one pairing [attempt] while the bridge says its link button is not pressed yet, so the
 * operator only has to press it. Stops at the first success or other failure, or after [attempts]
 * tries; the last result is returned, still [HueClient.Result.waitingForLinkButton] when time ran out.
 */
internal suspend fun awaitLinkButton(
    attempts: Int = LINK_BUTTON_ATTEMPTS,
    intervalMs: Long = LINK_BUTTON_INTERVAL_MS,
    pause: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> HueClient.Result,
): HueClient.Result {
    require(attempts > 0) { "At least one attempt." }
    var result = attempt()
    repeat(attempts - 1) {
        if (!result.waitingForLinkButton) return result
        pause(intervalMs)
        result = attempt()
    }
    return result
}
