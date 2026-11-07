package dev.padermodern.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Benchmark harness, skipped unless `BENCH_URL` is set (see tools/bench/run.sh). Asks the backend for every case in
 * `BENCH_CASES`, then runs the app's own mapping and ranking in both list modes and writes one JSON line per case to
 * `BENCH_OUT`. The adapter in BenchAdapter.kt keeps this file compiling against older app versions.
 */
class BenchSuiteTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun run() {
        val base = System.getenv("BENCH_URL")
        assumeTrue("BENCH_URL not set", base != null)
        val cases = json.parseToJsonElement(File(System.getenv("BENCH_CASES")).readText()).jsonArray
        val out = File(System.getenv("BENCH_OUT"))
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.MINUTES).build()
        val pool = Executors.newFixedThreadPool(System.getenv("BENCH_THREADS")?.toInt() ?: 2)
        val lines = cases.map { c ->
            pool.submit<String?> {
                val o = c.jsonObject
                val arrive = o["arrive"]!!.jsonPrimitive.boolean
                if (arrive && !BenchAdapter.ARRIVAL) return@submit null
                val transfer = o["transfer"]!!.jsonPrimitive.int
                val time = o["time"]!!.jsonPrimitive.long
                val url = (base!!.trimEnd('/') + "/v1/plan/static").toHttpUrl().newBuilder()
                    .addQueryParameter("from", o["from"]!!.jsonPrimitive.content)
                    .addQueryParameter("to", o["to"]!!.jsonPrimitive.content)
                    .addQueryParameter("time", time.toString())
                    .addQueryParameter("transfer", transfer.toString())
                    .addQueryParameter("changes", "2")
                    .apply { if (arrive) addQueryParameter("arrive", "1") }
                    .build()
                val t0 = System.nanoTime()
                val (code, body) = runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
                }.getOrElse { -1 to (it.message ?: "") }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val plan = if (code == 200) runCatching { json.decodeFromString<BPlan>(body) }.getOrNull() else null
                val routes = plan?.let { BenchAdapter.routes(it, o["from_name"]!!.jsonPrimitive.content, o["to_name"]!!.jsonPrimitive.content) }.orEmpty()
                val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(time), berlin)
                val window = TripSearch.window(at, arrive)
                buildJsonObject {
                    put("id", o["id"]!!.jsonPrimitive.int)
                    put("ms", ms); put("code", code); put("itins", plan?.itins?.size ?: 0)
                    put("variety", list(BenchAdapter.rank(routes, window, transfer, variety = true)))
                    put("trains", list(BenchAdapter.rank(routes, window, transfer, variety = false)))
                }.toString()
            }
        }.mapNotNull { it.get() }
        pool.shutdown()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun epoch(s: String?) = s?.let(::parseTime)?.toEpochSecond() ?: 0L

    private fun list(routes: List<Route>): JsonArray = buildJsonArray {
        routes.forEach { r ->
            addJsonObject {
                put("dep", epoch(r.departureDate)); put("arr", epoch(r.arrivalDate)); put("changes", r.changes)
                put("legs", buildJsonArray {
                    r.partialRoutes.forEach { l ->
                        addJsonObject {
                            put("walk", l.isWalk); put("line", l.shortName ?: ""); put("type", l.type)
                            put("dep", epoch(l.departureDate)); put("arr", epoch(l.arrivalDate))
                            put("from", l.stations.firstOrNull()?.name ?: ""); put("to", l.stations.lastOrNull()?.name ?: "")
                            put("stops", l.stations.size)
                        }
                    }
                })
            }
        }
    }
}
