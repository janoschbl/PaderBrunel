package dev.padermodern.data

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class BusNames(val lines: Map<Int, String>, val destinations: Map<Int, String>, val stops: Map<Int, String>)

class ApiException(message: String) : IOException(message)

object SrsApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    suspend fun bluetoothDatabase(): ByteArray = withContext(Dispatchers.IO) {
        val url = "https://service.ivanto.de/srs/api/v1/bluetooth/?tenant=pader&bundleIdentifier=de.ivanto.padersprinter" +
            "&appVersion=Android%20${android.os.Build.VERSION.RELEASE}---8.7.8-9872&filename=Padersprinter"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Bluetooth-Tabelle: Server antwortet mit ${response.code}")
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw ApiException("Bluetooth-Tabelle ist zu groß")
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            check(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray())) { "Ungültige Bluetooth-Tabelle" }
            bytes
        }
    }
}

object BusNameRepository {
    fun read(file: File): BusNames = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        fun table(name: String): Map<Int, String> = buildMap {
            db.rawQuery("SELECT id, text FROM $name LIMIT 10001", null).use { rows ->
                check(rows.count <= 10_000) { "Bluetooth-Tabelle ist zu groß" }
                while (rows.moveToNext()) {
                    val id = rows.getString(0)?.toIntOrNull() ?: continue
                    val text = rows.getString(1)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: continue
                    put(id, text)
                }
            }
        }
        BusNames(table("fixed_lines"), table("fixed_destinations"), table("stations"))
    }
}
