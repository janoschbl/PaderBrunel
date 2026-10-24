package dev.padermodern.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.padermodern.data.BusNames
import dev.padermodern.data.SrsApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object BusNameRepository {
    suspend fun load(context: Context): BusNames = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "bus_names.sqlite")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() in 0..86_400_000) {
            runCatching { read(file) }.getOrNull()?.let { return@withContext it }
        }
        val bytes = SrsApi.bluetoothDatabase()
        check(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray())) { "Ungültige Bluetooth-Tabelle" }
        val staging = File(context.cacheDir, "bus_names.download")
        try {
            staging.writeBytes(bytes)
            val names = read(staging)
            check(staging.renameTo(file)) { "Bluetooth-Tabelle konnte nicht gespeichert werden" }
            names
        } finally {
            staging.delete()
        }
    }

    internal fun read(file: File): BusNames = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
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
