package fi.goodconsulting.kaukosaadin.ui

import java.time.format.DateTimeFormatter

/** One status change with the wall-clock time it appeared. */
internal data class LogLine(
    val time: String,
    val text: String,
)

internal val LogClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Newest-first cap: bounds the panel without a second, nested scroll container. */
internal const val DEBUG_LOG_LIMIT = 40
