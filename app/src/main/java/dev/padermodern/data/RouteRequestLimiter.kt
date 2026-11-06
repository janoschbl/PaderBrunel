package dev.padermodern.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Keeps fast route searches independent from the broader extended-search lane. */
class RouteRequestLimiter(
    fastParallelism: Int = SearchPolicy.ROUTE_PARALLELISM,
    extendedParallelism: Int = SearchPolicy.ROUTE_PARALLELISM,
) {
    private val fast = Semaphore(fastParallelism.also { require(it > 0) })
    private val extended = Semaphore(extendedParallelism.also { require(it > 0) })

    suspend fun <T> run(maxChanges: Int, load: suspend () -> T): T =
        (if (maxChanges < 2) fast else extended).withPermit { load() }
}
