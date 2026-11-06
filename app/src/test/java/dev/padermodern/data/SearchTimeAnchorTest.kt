package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class SearchTimeAnchorTest {
    private val first = ZonedDateTime.parse("2026-10-27T12:00:00+01:00[Europe/Berlin]")
    private val second = first.plusMinutes(1)

    @Test fun nowDoesNotJumpAcrossMinuteBoundaryUntilAnchorExpires() {
        var ticks = 0L
        val anchor = SearchTimeAnchor { ticks }
        assertEquals(first, anchor.resolve(null, first))
        ticks += 59_999
        assertEquals(first, anchor.resolve(null, second))
        ticks += 1
        assertEquals(second, anchor.resolve(null, second))
    }

    @Test fun explicitTimeIsReturnedUnchangedAndDoesNotReplaceNowAnchor() {
        var ticks = 0L
        val anchor = SearchTimeAnchor { ticks }
        anchor.resolve(null, first)
        val explicit = first.plusDays(3).withSecond(47).withNano(123)
        assertEquals(explicit, anchor.resolve(explicit, second))
        assertEquals(first, anchor.resolve(null, second))
        ticks = SearchPolicy.NOW_ANCHOR_TTL_MILLIS
        assertEquals(second, anchor.resolve(null, second))
    }

    @Test fun midnightDoesNotReusePreviousServiceDate() {
        val late = first.withHour(23).withMinute(59).withSecond(59)
        val anchor = SearchTimeAnchor { 0 }
        anchor.resolve(null, late)
        assertEquals(late.plusSeconds(2), anchor.resolve(null, late.plusSeconds(2)))
    }
}
