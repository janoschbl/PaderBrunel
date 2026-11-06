package dev.padermodern.data

import java.time.ZonedDateTime

/** Keeps a stable "now" for transient UI edits without changing explicit user-selected times. */
class SearchTimeAnchor(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var anchor: ZonedDateTime? = null
    private var capturedAt: Long = Long.MIN_VALUE

    fun resolve(explicit: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        if (explicit != null) return explicit
        val current = nowMillis()
        if (anchor == null || anchor!!.toLocalDate() != now.toLocalDate() || anchor!!.zone != now.zone ||
            current - capturedAt >= SearchPolicy.NOW_ANCHOR_TTL_MILLIS || current < capturedAt) {
            anchor = now
            capturedAt = current
        }
        return anchor!!
    }
}
