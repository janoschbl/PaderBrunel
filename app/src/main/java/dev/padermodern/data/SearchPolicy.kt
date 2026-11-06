package dev.padermodern.data

object SearchPolicy {
    const val SCHEDULE_TTL_MILLIS = 15 * 60_000L
    const val SCHEDULE_REFRESH_MILLIS = 3 * 60_000L
    const val DEPARTURE_TTL_MILLIS = 30_000L
    const val NOW_ANCHOR_TTL_MILLIS = 60_000L
    const val ROUTE_PARALLELISM = 3
    const val MAX_STOP_PAIRS = 4
    const val RETRY_DELAY_MILLIS = 400L
    const val HEDGE_AFTER_MILLIS = 2_500L
    const val MAX_HEDGES_IN_FLIGHT = 2
    const val DISK_MATCH_BEHIND_MILLIS = 20 * 60_000L
    const val DISK_MATCH_AHEAD_MILLIS = 4 * 60_000L
    const val DISK_PAST_GRACE_MILLIS = 30 * 60_000L
    const val PREFETCH_PER_HOUR = 4
    const val PREFETCH_AT_STARTUP = 2
    const val PREFETCH_MIN_BATTERY_PERCENT = 20
    const val HUB_PADERBORN_HBF = "23207000"
    const val CRAWL_MAX_STOPS = 24
    const val DISK_CACHE_MAX_AGE_MILLIS = 24 * 60 * 60_000L
}
