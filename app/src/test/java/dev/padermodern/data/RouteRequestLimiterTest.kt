package dev.padermodern.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouteRequestLimiterTest {
    @Test fun lanesAreIndependentAndAllowEightEach() = runTest {
        val limiter = RouteRequestLimiter(8, 8)
        var fastActive = 0; var fastPeak = 0
        var extendedActive = 0; var extendedPeak = 0
        coroutineScope {
            (0 until 16).map { i -> async {
                limiter.run(if (i < 8) 1 else 2) {
                    if (i < 8) { fastActive++; fastPeak = maxOf(fastPeak, fastActive) }
                    else { extendedActive++; extendedPeak = maxOf(extendedPeak, extendedActive) }
                    kotlinx.coroutines.delay(1_000)
                    if (i < 8) fastActive-- else extendedActive--
                }
            } }.awaitAll()
        }
        assertEquals(8, fastPeak)
        assertEquals(8, extendedPeak)
    }

    @Test fun cancellingCallerReleasesPermit() = runTest {
        val limiter = RouteRequestLimiter(1, 1)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waiter = async { limiter.run(0) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        entered.await()
        waiter.cancelAndJoin()
        assertEquals(7, limiter.run(0) { 7 })
    }

    @Test fun simulatedLatencyShowsEightWideFastAndExtendedLanes() = runTest {
        suspend fun simulated(parallelism: Int, requests: Int, maxChanges: Int): Pair<Long, Int> {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(scheduler)
            var operations = 0
            val limiter = RouteRequestLimiter(parallelism, parallelism)
            val jobs = (0 until requests).map { kotlinx.coroutines.CoroutineScope(dispatcher).async {
                limiter.run(maxChanges) {
                    operations++
                    kotlinx.coroutines.delay(1_000)
                }
            } }
            jobs.forEach { it.start() }
            scheduler.advanceUntilIdle()
            jobs.forEach { it.await() }
            return scheduler.currentTime to operations
        }
        val oldExtended = simulated(parallelism = 2, requests = 8, maxChanges = 2)
        val newExtended = simulated(parallelism = 8, requests = 8, maxChanges = 2)
        val oldFast = simulated(parallelism = 4, requests = 8, maxChanges = 1)
        val newFast = simulated(parallelism = 8, requests = 8, maxChanges = 1)
        assertEquals(4_000L, oldExtended.first)
        assertEquals(1_000L, newExtended.first)
        assertEquals(2_000L, oldFast.first)
        assertEquals(1_000L, newFast.first)
        assertEquals(8, newExtended.second)
        assertEquals(8, newFast.second)
        println("BENCHMARK simulateddelay extended=${oldExtended.first}->${newExtended.first}ms requests=${oldExtended.second}/${newExtended.second}; fast=${oldFast.first}->${newFast.first}ms requests=${oldFast.second}/${newFast.second} (simulated delay; not device benchmark)")
    }
}
