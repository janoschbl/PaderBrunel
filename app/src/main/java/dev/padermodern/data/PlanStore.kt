package dev.padermodern.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.builtins.ListSerializer

/** One cached provider search. [queryAt] is the departure/arrival time the search was run for. */
data class StoredPlan(val routes: List<Route>, val storedAt: Long, val queryAt: Long)

/** Persistent copy of [RoutePlans], so the first search after a restart is not a network search. */
interface PlanStore {
    /** Newest plan for [base] whose query time lies in [atMillis - behind, atMillis + ahead]. */
    fun load(base: String, atMillis: Long, behindMillis: Long, aheadMillis: Long): StoredPlan?
    fun save(base: String, queryAt: Long, storedAt: Long, routes: List<Route>)
    /** Drops searches whose query time is before [beforeMillis] or that were stored before [storedBeforeMillis]. */
    fun prune(beforeMillis: Long, storedBeforeMillis: Long)
}

class SqlitePlanStore(context: Context) : PlanStore {
    private val serializer = ListSerializer(Route.serializer())
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "plans.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE plans (base TEXT NOT NULL, query_at INTEGER NOT NULL, stored_at INTEGER NOT NULL, routes TEXT NOT NULL, PRIMARY KEY (base, query_at))")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS plans"); onCreate(db)
        }
    }

    override fun load(base: String, atMillis: Long, behindMillis: Long, aheadMillis: Long): StoredPlan? = try {
        helper.readableDatabase.rawQuery(
            "SELECT query_at, stored_at, routes FROM plans WHERE base = ? AND query_at BETWEEN ? AND ? ORDER BY ABS(query_at - ?) LIMIT 1",
            arrayOf(base, (atMillis - behindMillis).toString(), (atMillis + aheadMillis).toString(), atMillis.toString()),
        ).use { c ->
            if (!c.moveToFirst()) null
            else StoredPlan(SrsApi.json.decodeFromString(serializer, c.getString(2)), c.getLong(1), c.getLong(0))
        }
    } catch (e: Exception) { null }

    override fun save(base: String, queryAt: Long, storedAt: Long, routes: List<Route>) {
        try {
            val values = ContentValues().apply {
                put("base", base); put("query_at", queryAt); put("stored_at", storedAt)
                put("routes", SrsApi.json.encodeToString(serializer, routes))
            }
            helper.writableDatabase.insertWithOnConflict("plans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) { /* the cache is an optimization only */ }
    }

    override fun prune(beforeMillis: Long, storedBeforeMillis: Long) {
        try {
            helper.writableDatabase.delete("plans", "query_at < ? OR stored_at < ?", arrayOf(beforeMillis.toString(), storedBeforeMillis.toString()))
        } catch (_: Exception) {}
    }
}
