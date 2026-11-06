package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCacheTest {
    @Test
    fun concurrentCallersShareLoadAndCacheResult() = runBlocking {
        val cache = RequestCache<String, String>(this, ttlMillis = 100, maxEntries = 4)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        var loads = 0
        val first = async { cache.get("k") { loads++; started.complete(Unit); release.await() } }
        started.await()
        val second = async { cache.get("k") { loads++; "wrong" } }
        yield()
        release.complete("shared")
        assertEquals("shared", first.await())
        assertEquals("shared", second.await())
        assertEquals("shared", cache.get("k") { loads++; "wrong" })
        assertEquals(1, loads)
    }

    @Test
    fun cancellingOneWaiterLeavesSharedLoadRunning() = runBlocking {
        val cache = RequestCache<String, Int>(this, 100, 4)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        var finished = false
        val first = async {
            cache.get("k") { started.complete(Unit); try { release.await() } finally { finished = true } }
        }
        started.await()
        val second = async { cache.get("k") { error("duplicate load") } }
        yield()
        first.cancelAndJoin()
        assertFalse(finished)
        release.complete(7)
        assertEquals(7, second.await())
        assertTrue(finished)
    }

    @Test
    fun cancellingLastWaiterCancelsUnderlyingLoadAndEvicts() = runBlocking {
        val cache = RequestCache<String, Int>(this, 100, 4)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val waiter = async {
            cache.get("k") {
                started.complete(Unit)
                try { CompletableDeferred<Int>().await() } finally { cancelled.complete(Unit) }
            }
        }
        started.await()
        waiter.cancelAndJoin()
        cancelled.await()
        assertEquals(9, cache.get("k") { 9 })
    }

    @Test
    fun errorsAreNotCachedAndEmptyValuesAreCached() = runBlocking {
        val cache = RequestCache<String, List<Int>>(this, 100, 4)
        var loads = 0
        try {
            cache.get("error") { loads++; error("failure") }
        } catch (_: IllegalStateException) { }
        assertEquals(emptyList<Int>(), cache.get("error") { loads++; emptyList() })
        assertEquals(emptyList<Int>(), cache.get("error") { loads++; listOf(1) })
        assertEquals(2, loads)
    }

    @Test
    fun ttlStartsAtCompletionAndCompletedEntriesUseLruBound() = runBlocking {
        var now = 0L
        val cache = RequestCache<String, String>(this, ttlMillis = 10, maxEntries = 2, nowMillis = { now })
        var loads = 0
        assertEquals("A", cache.get("a") { loads++; "A" })
        now = 4
        assertEquals("B", cache.get("b") { loads++; "B" })
        now = 5
        assertEquals("A", cache.get("a") { loads++; "again A" }) // refresh A's LRU position
        assertEquals("C", cache.get("c") { loads++; "C" }) // evicts B
        assertEquals("A", cache.get("a") { loads++; "wrong" })
        assertEquals("B2", cache.get("b") { loads++; "B2" })
        now = 16
        assertEquals("A2", cache.get("a") { loads++; "A2" })
        assertEquals(5, loads)
    }

    @Test
    fun slowLoadGetsFullTtlAfterItCompletes() = runBlocking {
        var now = 0L
        val cache = RequestCache<String, Int>(this, 10, 2, nowMillis = { now })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        val result = async { cache.get("slow") { started.complete(Unit); release.await() } }
        started.await()
        now = 100
        release.complete(1)
        assertEquals(1, result.await())
        now = 109
        assertEquals(1, cache.get("slow") { error("expired too soon") })
        now = 110
        assertEquals(2, cache.get("slow") { 2 })
    }

    @Test
    fun capacityDoesNotEvictInFlightRequests() = runBlocking {
        val cache = RequestCache<String, Int>(this, 100, 1)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        val first = async { cache.get("first") { firstStarted.complete(Unit); release.await() } }
        val second = async { cache.get("second") { secondStarted.complete(Unit); release.await() } }
        firstStarted.await()
        secondStarted.await()
        val shared = async { cache.get("first") { error("active entry was evicted") } }
        yield()
        release.complete(7)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(7, shared.await())
    }
}
