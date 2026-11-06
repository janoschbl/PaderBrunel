package dev.padermodern.data

import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureRequestsTest {
    private val at = ZonedDateTime.parse("2026-10-27T10:42:37+01:00[Europe/Berlin]")
    private val sample = Departure(shortName = "S1")

    @Test fun warmReuseAndFreshRefresh() = runTest {
        var loads = 0
        val requests = DepartureRequests(this, { _, _ -> loads++; listOf(sample) })
        assertEquals(listOf(sample), requests.get("a", at))
        assertEquals(listOf(sample), requests.get("a", at, fresh = false))
        assertEquals(1, loads)
        requests.get("a", at, fresh = true)
        assertEquals(2, loads)
        requests.get("a", at, fresh = false)
        assertEquals(2, loads)
    }

    @Test fun concurrentFreshAndWarmCallersShareInFlightLoad() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var loads = 0
        val requests = DepartureRequests(this, { _, _ -> loads++; entered.complete(Unit); gate.await(); listOf(sample) })
        val first = async { requests.get("a", at) }
        entered.await()
        val second = async { requests.get("a", at, fresh = false) }
        yield()
        gate.complete(Unit)
        assertEquals(listOf(sample), first.await())
        assertEquals(listOf(sample), second.await())
        assertEquals(1, loads)
    }

    @Test fun cancelingOneWaiterDoesNotExposeOldWarmSnapshotDuringFreshLoad() = runTest {
        var loads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = DepartureRequests(this, { _, _ ->
            if (++loads > 1) { entered.complete(Unit); release.await() }
            listOf(sample.copy(shortName = "$loads"))
        })
        requests.get("a", at)
        val first = async { requests.get("a", at) }
        entered.await()
        val second = async { requests.get("a", at) }
        yield()
        first.cancelAndJoin()
        val warm = async { requests.get("a", at, fresh = false) }
        yield()
        assertTrue(!warm.isCompleted)
        release.complete(Unit)
        assertEquals("2", warm.await().single().shortName)
        second.await()
        assertEquals(2, loads)
    }

    @Test fun ttlStartsAtCompletionAndFailuresKeepGoodSnapshot() = runTest {
        var now = 0L
        var fail = false
        var loads = 0
        val requests = DepartureRequests(this, { _, _ ->
            loads++
            if (fail) error("temporary")
            listOf(sample)
        }, nowMillis = { now })
        requests.get("a", at)
        now = SearchPolicy.DEPARTURE_TTL_MILLIS - 1
        requests.get("a", at, fresh = false)
        assertEquals(1, loads)
        fail = true
        try { requests.get("a", at) } catch (_: IllegalStateException) { }
        fail = false
        requests.get("a", at, fresh = false)
        assertEquals(2, loads)
        now += 2
        requests.get("a", at, fresh = false)
        assertEquals(3, loads)
    }

    @Test fun emptyResultsAreWarmAndMinuteNormalizedForKeysAndLoads() = runTest {
        var loads = 0
        val loadedTimes = mutableListOf<ZonedDateTime>()
        val requests = DepartureRequests(this, { _, time -> loads++; loadedTimes += time; emptyList() })
        assertTrue(requests.get("a", at).isEmpty())
        assertTrue(requests.get("a", at.withSecond(59), fresh = false).isEmpty())
        assertEquals(1, loads)
        assertEquals(listOf(at.withSecond(0).withNano(0)), loadedTimes)
        requests.get("a", at.plusDays(1))
        assertEquals(2, loads)
        requests.get("a", at.withZoneSameInstant(java.time.ZoneId.of("UTC")))
        assertEquals(3, loads)
    }
}
