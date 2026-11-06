package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HedgeTest {
    @Test fun fastFirstAttemptNeverHedges() = runTest {
        var calls = 0
        val r = hedged(2_500, HedgeBudget()) { calls++; "ok" }
        assertEquals("ok", r); assertEquals(1, calls)
    }

    @Test fun stalledFirstAttemptIsOvertakenByHedge() = runTest {
        var calls = 0
        val r = hedged(2_500, HedgeBudget()) {
            val n = ++calls
            if (n == 1) delay(20_000)
            "attempt$n"
        }
        assertEquals("attempt2", r)
        assertEquals(2_500, currentTime)
    }

    @Test fun hedgeIsSkippedWhenBudgetIsSpent() = runTest {
        val budget = HedgeBudget(0)
        var calls = 0
        val r = hedged(2_500, budget) { calls++; delay(6_000); "slow" }
        assertEquals("slow", r); assertEquals(1, calls)
    }

    @Test fun failureOfOneAttemptDoesNotHideTheOther() = runTest {
        var calls = 0
        val r = hedged(1_000, HedgeBudget()) {
            if (++calls == 1) { delay(1_500); throw java.io.IOException("502") } else { delay(2_000); "second" }
        }
        assertEquals("second", r)
    }

    @Test fun failsWhenEveryAttemptFails() = runTest {
        try {
            hedged(500, HedgeBudget()) { delay(1_000); throw java.io.IOException("down") }
            fail()
        } catch (e: java.io.IOException) { assertEquals("down", e.message) }
    }

    @Test fun budgetIsReleasedAfterwards() = runTest {
        val budget = HedgeBudget(1)
        hedged(100, budget) { delay(500); 1 }
        assertTrue(budget.tryAcquire())
    }
}
