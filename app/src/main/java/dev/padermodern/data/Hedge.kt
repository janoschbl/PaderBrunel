package dev.padermodern.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/** Caps duplicate requests that hedging may have in flight at once. */
class HedgeBudget(private val max: Int = SearchPolicy.MAX_HEDGES_IN_FLIGHT) {
    private val used = AtomicInteger()
    fun tryAcquire(): Boolean {
        while (true) {
            val n = used.get()
            if (n >= max) return false
            if (used.compareAndSet(n, n + 1)) return true
        }
    }
    fun release() { used.decrementAndGet() }
}

/**
 * Runs [block]; if it has not answered after [afterMillis], starts one more copy and returns the first
 * success. A failed first attempt does not hide a still-running hedge, and vice versa.
 */
suspend fun <T> hedged(afterMillis: Long, budget: HedgeBudget, block: suspend () -> T): T = coroutineScope {
    val result = CompletableDeferred<T>()
    var failures = 0
    var started = 1
    val lock = Any()
    fun launchAttempt() = async {
        try {
            result.complete(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val last = synchronized(lock) { ++failures >= started }
            if (last) result.completeExceptionally(e)
        }
    }
    launchAttempt()
    async {
        delay(afterMillis)
        if (!result.isCompleted && budget.tryAcquire()) {
            try {
                synchronized(lock) { started++ }
                launchAttempt().join()
            } finally { budget.release() }
        }
    }
    try {
        result.await()
    } finally {
        coroutineContext.cancelChildren()
    }
}
