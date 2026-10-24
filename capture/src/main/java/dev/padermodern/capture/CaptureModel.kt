package dev.padermodern.capture

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val CaptureJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

@Serializable
data class Event(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val sequence: Long,
    val eventId: String,
    val type: String,
    val timestampUtc: String,
    val elapsedRealtimeNanos: Long,
    val profileId: String? = null,
    val operationId: String? = null,
    val data: JsonObject,
)

@Serializable
data class Decoded(
    val result: String,
    val error: String? = null,
    val protocol: Int? = null,
    val company: Int? = null,
    val flags: Int? = null,
    val lineId: Int? = null,
    val destinationId: Int? = null,
    val nextStopId: Int? = null,
    val hardwareId: Int? = null,
    val course: Int? = null,
    val unknownHex: String? = null,
    val simulator: Boolean = false,
) {
    val ok get() = result == "ok"
}

@Serializable
data class RawAd(
    val receivedMs: Long,
    val address: String,
    val addressType: Int? = null,
    val deviceName: String? = null,
    val rssi: Int,
    val scanTimestampNanos: Long? = null,
    val bytes: String,
    val length: Int,
    val hash: String,
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, String> = emptyMap(),
    val manufacturer: Map<String, String> = emptyMap(),
    val connectable: Boolean? = null,
    val legacy: Boolean? = null,
    val primaryPhy: Int? = null,
    val secondaryPhy: Int? = null,
    val advertisingSid: Int? = null,
    val txPower: Int? = null,
    val dataStatus: Int? = null,
    val candidate: String,
    val parserVersion: Int = 1,
    val decoded: Decoded,
    val line: String? = null,
    val destination: String? = null,
    val nextStop: String? = null,
    val lookupHash: String? = null,
    val test: Boolean = false,
)

class RawScan(
    val receivedMs: Long,
    val bytes: ByteArray,
    val address: String,
    val addressType: Int?,
    val name: String?,
    val rssi: Int,
    val scanTimestampNanos: Long?,
    val connectable: Boolean?, val legacy: Boolean?, val primaryPhy: Int?, val secondaryPhy: Int?,
    val sid: Int?, val txPower: Int?, val dataStatus: Int?,
    val test: Boolean = false,
)

class Structures(val uuids: List<String>, val serviceData: Map<String, String>, val manufacturer: List<ByteArray>, val error: String?)

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

private fun uuidOf(le: ByteArray): String = when (le.size) {
    2, 4 -> "%08x-0000-1000-8000-00805f9b34fb".format(le.foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xff) shl (8 * i)) })
    else -> le.reversedArray().hex().let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
}

fun parseStructures(raw: ByteArray): Structures {
    val uuids = mutableListOf<String>()
    val serviceData = linkedMapOf<String, String>()
    val manufacturer = mutableListOf<ByteArray>()
    var error: String? = null
    var i = 0
    while (i < raw.size) {
        val len = raw[i].toInt() and 0xff
        if (len == 0) break
        if (i + len + 1 > raw.size) { error = "AD-Struktur bei Byte $i ragt über das Ende ($len Byte)"; break }
        val type = raw[i + 1].toInt() and 0xff
        val body = raw.copyOfRange(i + 2, i + len + 1)
        val width = when (type) { 0x02, 0x03 -> 2; 0x04, 0x05 -> 4; 0x06, 0x07 -> 16; else -> 0 }
        when {
            width > 0 -> if (body.size % width != 0) error = error ?: "UUID-Liste (Typ 0x%02x) hat ungültige Länge ${body.size}".format(type)
                else body.toList().chunked(width).forEach { uuids += uuidOf(it.toByteArray()) }
            type == 0x16 && body.size >= 2 -> serviceData[uuidOf(body.copyOfRange(0, 2))] = body.copyOfRange(2, body.size).hex()
            type == 0x20 && body.size >= 4 -> serviceData[uuidOf(body.copyOfRange(0, 4))] = body.copyOfRange(4, body.size).hex()
            type == 0x21 && body.size >= 16 -> serviceData[uuidOf(body.copyOfRange(0, 16))] = body.copyOfRange(16, body.size).hex()
            type == 0xff -> manufacturer += body
        }
        i += len + 1
    }
    return Structures(uuids, serviceData, manufacturer, error)
}

private const val BUS_UUID = "00001f1f-0000-1000-8000-00805f9b34fb"

private fun word(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)

fun candidateReason(s: Structures): String? = when {
    BUS_UUID in s.uuids -> "Service 0x1f1f"
    s.manufacturer.any { it.size >= 2 && (word(it, 0) == 0x7d78 || word(it, 0) == 0x7d79) } -> "Hersteller 0x7d78"
    else -> null
}

fun decodeBus(s: Structures): Decoded {
    val data = s.manufacturer.firstOrNull { it.size >= 2 && (word(it, 0) == 0x7d78 || word(it, 0) == 0x7d79) }
        ?: s.manufacturer.firstOrNull()
        ?: return Decoded("no_manufacturer", s.error ?: "Keine Herstellerdaten im Paket")
    val simulator = data.size >= 2 && word(data, 0) == 0x7d79
    if (data.size < 4) return Decoded("too_short", "Herstellerdaten nur ${data.size} Byte", unknownHex = data.hex())
    val protocol = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
    if (data.size < 18) return Decoded("too_short", "Herstellerdaten nur ${data.size} Byte, erwartet 18", protocol = protocol, simulator = simulator, unknownHex = data.hex())
    val base = Decoded(
        result = "ok", protocol = protocol, company = data[5].toInt() and 0xff, flags = data[4].toInt() and 0xff,
        lineId = word(data, 6), destinationId = word(data, 8), nextStopId = word(data, 10),
        hardwareId = word(data, 12), course = word(data, 14), simulator = simulator,
        unknownHex = data.copyOfRange(16, data.size).hex().ifEmpty { null },
    )
    return when {
        word(data, 0) != 0x7d78 && !simulator -> base.copy(result = "unknown_manufacturer", error = "Hersteller-ID 0x%04x".format(word(data, 0)))
        protocol != 16 -> base.copy(result = "unknown_protocol", error = "Protokoll $protocol statt 16")
        base.company != 4 -> base.copy(result = "unknown_company", error = "Firmen-ID ${base.company} statt 4")
        else -> base
    }
}

val Decoded.resultLabel: String get() = when (result) {
    "ok" -> if (simulator) "OK · Simulator" else "OK"
    "no_manufacturer" -> "Keine Herstellerdaten"
    "too_short" -> "Zu kurz"
    "unknown_protocol" -> "Unbekanntes Protokoll"
    "unknown_company" -> "Unbekannte Firma"
    "unknown_manufacturer" -> "Unbekannter Hersteller"
    "parser_exception" -> "Parser-Fehler"
    else -> result
}

fun profileIdOf(sessionId: String, address: String): String = "p-" + sha256("$sessionId|$address".toByteArray()).take(8)
