package dev.padermodern.data

import org.junit.Assert.*
import org.junit.Test

class SearchTimeFormatTest {
    @Test fun providerAndIsoTimesResolveToSameInstant() {
        val provider = parseTime("2026-10-27T10:00:00+0100")!!
        val iso = parseTime("2026-10-27T10:00:00+01:00[Europe/Berlin]")!!
        assertTrue(provider.isEqual(iso))
        assertNull(parseTime("invalid"))
        assertEquals(provider, parseTime(provider.iso()))
    }
}
