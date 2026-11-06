package dev.padermodern.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import java.time.ZonedDateTime

/** How often a route was searched in one (weekday, hour) bucket, and at which minute of the hour on average. */
data class Habit(
    val from: Station,
    val to: Station,
    val arrival: Boolean,
    val weekday: Int,
    val hour: Int,
    val count: Int,
    val avgMinute: Int,
) {
    val routeKey: String get() = "${from.key}|${to.key}|$arrival"
    val typicalMinuteOfDay: Int get() = hour * 60 + avgMinute
}

interface HabitStore {
    fun record(from: Station, to: Station, arrival: Boolean, weekday: Int, hour: Int, minute: Int)
    fun all(): List<Habit>
}

object HabitPredictor {
    const val MIN_COUNT = 2

    /** Routes usually searched on this weekday between [now] - [lookBehindMin] and [now] + [horizonMin], most frequent first. */
    fun predict(habits: List<Habit>, now: ZonedDateTime, limit: Int, horizonMin: Int = 60, lookBehindMin: Int = 10): List<Habit> {
        val minuteNow = now.hour * 60 + now.minute
        return habits.asSequence()
            .filter { it.weekday == now.dayOfWeek.value && it.count >= MIN_COUNT && !it.arrival }
            .filter { it.typicalMinuteOfDay in (minuteNow - lookBehindMin)..(minuteNow + horizonMin) }
            .groupBy { it.routeKey }
            .map { (_, group) -> group.maxBy { it.count } }
            .sortedWith(compareByDescending<Habit> { it.count }.thenBy { it.typicalMinuteOfDay })
            .take(limit)
            .toList()
    }

    /** Minutes from [now] until the next habit worth warming for, or null when nothing is learned yet. */
    fun nextWarmDelayMinutes(habits: List<Habit>, now: ZonedDateTime, leadMin: Int = 5): Long? {
        val minuteNow = now.hour * 60 + now.minute
        return habits.filter { it.count >= MIN_COUNT && !it.arrival }
            .map { h ->
                val day = (h.weekday - now.dayOfWeek.value + 7) % 7
                var delta = day * 1440 + h.typicalMinuteOfDay - minuteNow - leadMin
                if (delta < 1) delta += 7 * 1440
                delta.toLong()
            }.minOrNull()
    }
}

class SqliteHabitStore(context: Context) : HabitStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "habits.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE habits (route TEXT NOT NULL, weekday INTEGER NOT NULL, hour INTEGER NOT NULL, count INTEGER NOT NULL, minute_sum INTEGER NOT NULL, " +
                    "from_json TEXT NOT NULL, to_json TEXT NOT NULL, arrival INTEGER NOT NULL, PRIMARY KEY (route, weekday, hour))",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }

    override fun record(from: Station, to: Station, arrival: Boolean, weekday: Int, hour: Int, minute: Int) {
        try {
            val route = "${from.key}|${to.key}|$arrival"
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO habits (route, weekday, hour, count, minute_sum, from_json, to_json, arrival) VALUES (?,?,?,1,?,?,?,?) " +
                        "ON CONFLICT(route, weekday, hour) DO UPDATE SET count = count + 1, minute_sum = minute_sum + excluded.minute_sum",
                    arrayOf<Any>(route, weekday, hour, minute, SrsApi.json.encodeToString(from), SrsApi.json.encodeToString(to), if (arrival) 1 else 0),
                )
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Exception) {}
    }

    override fun all(): List<Habit> = try {
        helper.readableDatabase.rawQuery("SELECT weekday, hour, count, minute_sum, from_json, to_json, arrival FROM habits", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Habit(
                        from = SrsApi.json.decodeFromString<Station>(c.getString(4)),
                        to = SrsApi.json.decodeFromString<Station>(c.getString(5)),
                        arrival = c.getInt(6) == 1, weekday = c.getInt(0), hour = c.getInt(1),
                        count = c.getInt(2), avgMinute = c.getInt(3) / c.getInt(2),
                    ),
                )
            }
        }
    } catch (e: Exception) { emptyList() }
}
