package fi.goodconsulting.kaukosaadin.device.companion

/** Skip availability from Companion's pushed `_iMC` flags, not from session readiness. */
data class CompanionSkipSupport(
    val forward: Boolean = false,
    val backward: Boolean = false,
) {
    internal fun allows(seconds: Double) = if (seconds > 0) forward else backward

    companion object {
        internal fun fromFlags(value: Any?): CompanionSkipSupport {
            val flags = (value as? Long)?.takeIf { it >= 0 } ?: 0L
            return CompanionSkipSupport(forward = flags and 0x0200L != 0L, backward = flags and 0x0400L != 0L)
        }
    }
}
