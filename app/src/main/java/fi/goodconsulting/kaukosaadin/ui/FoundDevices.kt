package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/** Where one kind's scan stands on Add a device. */
internal sealed interface ScanOutcome {
    /** Still scanning; [candidates] is what has answered so far, empty until the first reply. */
    data class Scanning(
        val candidates: List<Candidate>,
    ) : ScanOutcome

    /** Finished; [candidates] is everything the scan found. */
    data class Found(
        val candidates: List<Candidate>,
    ) : ScanOutcome

    /** The scan itself failed; its cause is logged where the scan runs, the screen only says what to try. */
    data object Failed : ScanOutcome
}

/** What this kind has to list, whether it is still scanning or has finished. */
private val ScanOutcome?.foundSoFar: List<Candidate>
    get() =
        when (this) {
            is ScanOutcome.Scanning -> candidates
            is ScanOutcome.Found -> candidates
            ScanOutcome.Failed, null -> emptyList()
        }

/** One scanned device as Add a device lists it. */
internal data class ScanRow(
    val candidate: Candidate,
    /** Already a saved device (same kind and host); listed, but not offered for pairing. */
    val saved: Boolean,
    /** Another row has the same name, so the address is shown to tell them apart. */
    val showHost: Boolean,
) {
    val subtitle: String
        get() = listOfNotNull(candidate.kind.label, candidate.detail, candidate.host.takeIf { showHost }).joinToString(" · ")
}

/** Every kind's scan merged into the one list and the one line of status above it. */
internal data class ScanView(
    val rows: List<ScanRow>,
    val scanning: Boolean,
    val headline: String,
    /** Names the kinds whose scan failed while others worked; null when none failed or all did. */
    val failureNote: String?,
) {
    val newCount: Int get() = rows.count { !it.saved }
}

/**
 * Merges each kind's [outcomes] into one list: devices not yet [saved] first, then by kind in
 * [order] and by name. A kind missing from [outcomes] counts as still scanning, and a kind still
 * scanning already lists what it has found.
 */
internal fun mergeScan(
    outcomes: Map<DeviceKind, ScanOutcome>,
    saved: List<SavedDevice>,
    order: List<DeviceKind> = DeviceIntegrations.all.map { it.kind },
): ScanView {
    val candidates = order.flatMap { outcomes[it].foundSoFar }
    val nameCounts = candidates.groupingBy { it.name.lowercase() }.eachCount()
    val rows =
        candidates
            .map { candidate ->
                ScanRow(
                    candidate = candidate,
                    saved = saved.any { it.kind == candidate.kind && it.host == candidate.host },
                    showHost = (nameCounts[candidate.name.lowercase()] ?: 0) > 1,
                )
            }.sortedWith(
                compareBy<ScanRow> { it.saved }
                    .thenBy { order.indexOf(it.candidate.kind) }
                    .thenBy { it.candidate.name.lowercase() },
            )
    val scanning = order.any { outcomes[it] is ScanOutcome.Scanning || outcomes[it] == null }
    val failed = order.filter { outcomes[it] == ScanOutcome.Failed }
    val allFailed = failed.size == order.size
    val newCount = rows.count { !it.saved }
    val headline =
        when {
            scanning -> "Looking for devices on your Wi-Fi…"
            allFailed -> "Couldn't scan your network. Check that you're connected to Wi-Fi."
            newCount == 1 -> "Found 1 new device"
            newCount > 1 -> "Found $newCount new devices"
            rows.isNotEmpty() -> "No new devices found"
            else -> "No devices found"
        }
    val failureNote =
        failed
            .takeUnless { it.isEmpty() || allFailed }
            ?.joinToString(" and ") { "${it.label}s" }
            ?.let { "Couldn't scan for $it." }
    return ScanView(rows, scanning, headline, failureNote)
}
