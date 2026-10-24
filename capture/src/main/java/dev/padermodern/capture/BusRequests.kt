package dev.padermodern.capture

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class BusAction(val label: String, val route: String, val flag: Int) {
    Stop("Haltewunsch", "stopRequest", 1),
    Door("Türsignal", "doorsignalRequest", 4),
    Service("Einstiegshilfe", "serviceRequest", 2);

    val url get() = "https://service.ivanto.de/busaccess/$route"
    fun supported(ad: RawAd?) = ad?.decoded?.let { it.ok && (it.flags ?: 0) and flag != 0 } == true
}

internal fun busRequest(action: BusAction, address: String): Request {
    require(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(address)) { "Ungültige Bluetooth-Adresse" }
    val body = buildJsonObject { put("btMac", address) }.toString()
    return Request.Builder().url(action.url)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("Accept", "application/json").build()
}

internal data class BusReply(val code: Int, val body: String, val truncated: Boolean) {
    val accepted get() = !truncated && code in 200..299 && runCatching {
        CaptureJson.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)
}

internal object BusRequests {
    // a timeout can mean it was delivered, never retry
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()

    fun send(request: Request, httpClient: OkHttpClient = client): BusReply = httpClient.newCall(request).execute().use { response ->
        val bytes = response.peekBody(65_537).bytes()
        BusReply(response.code, bytes.take(65_536).toByteArray().toString(Charsets.UTF_8), bytes.size > 65_536)
    }
}
