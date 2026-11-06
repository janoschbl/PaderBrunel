package dev.padermodern.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

/** A bounded, TTL cache that coalesces concurrent requests for the same key. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestCache<K, V>(
    private val scope: CoroutineScope,
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry<V>(val deferred: Deferred<Result<V>>) {
        var waiters: Int = 0
        var hasValue: Boolean = false
        var expiresAt: Long = 0
    }

    private val lock = Any()
    // LinkedHashMap's access order gives completed values LRU eviction.
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    init {
        require(ttlMillis >= 0)
        require(maxEntries > 0)
    }

    suspend fun get(key: K, load: suspend () -> V): V {
        var shouldStart = false
        val entry = synchronized(lock) {
            val existing = entries[key]
            if (existing?.hasValue == true) {
                if (nowMillis() < existing.expiresAt) return existing.deferred.getCompleted().getOrThrow()
                entries.remove(key)
            }

            val active = entries[key]
            if (active != null) {
                active.waiters++
                active
            } else {
                lateinit var created: Entry<V>
                val deferred = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    // A request failure belongs to its waiters, not to the owning scope.
                    try {
                        Result.success(load())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                created = Entry(deferred)
                created.waiters = 1
                entries[key] = created
                shouldStart = true
                deferred.invokeOnCompletion { cause ->
                    synchronized(lock) {
                        if (entries[key] !== created) return@synchronized
                        if (cause != null || deferred.getCompleted().isFailure) {
                            entries.remove(key)
                        } else {
                            created.hasValue = true
                            created.expiresAt = nowMillis() + ttlMillis
                            trimCompleted()
                        }
                    }
                }
                created
            }
        }

        if (shouldStart) entry.deferred.start()
        try {
            return entry.deferred.await().getOrThrow()
        } finally {
            synchronized(lock) {
                if (entries[key] === entry) {
                    entry.waiters--
                    if (entry.waiters == 0 && !entry.deferred.isCompleted) {
                        entries.remove(key)
                        entry.deferred.cancel()
                    }
                }
            }
        }
    }

    private fun trimCompleted() {
        var completedCount = entries.values.count { it.hasValue }
        if (completedCount <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && completedCount > maxEntries) {
            if (iterator.next().value.hasValue) {
                iterator.remove()
                completedCount--
            }
        }
    }
}
