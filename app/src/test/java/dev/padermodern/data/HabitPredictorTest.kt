package dev.padermodern.data

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HabitPredictorTest {
    private fun st(id: String) = Station(type = "stop", id = id, name = id)
    private fun habit(to: String, weekday: Int, hour: Int, count: Int, minute: Int = 30, arrival: Boolean = false) =
        Habit(st("A"), st(to), arrival, weekday, hour, count, minute)
    private val monday0745 = ZonedDateTime.of(2026, 11, 2, 7, 45, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test fun picksMostFrequentRoutesOfThisWeekdayAndHour() {
        val picks = HabitPredictor.predict(listOf(
            habit("B", 1, 8, 9), habit("C", 1, 8, 4), habit("D", 1, 8, 7), habit("E", 2, 8, 20), habit("F", 1, 17, 30),
        ), monday0745, limit = 2)
        assertEquals(listOf("B", "D"), picks.map { it.to.id })
    }

    @Test fun oneOffSearchesAreNotHabits() {
        assertTrue(HabitPredictor.predict(listOf(habit("B", 1, 8, 1)), monday0745, 2).isEmpty())
    }

    @Test fun arrivalSearchesAreNeverPrefetched() {
        assertTrue(HabitPredictor.predict(listOf(habit("B", 1, 8, 5, arrival = true)), monday0745, 2).isEmpty())
    }

    @Test fun sameRouteInTwoBucketsIsReturnedOnce() {
        val picks = HabitPredictor.predict(listOf(habit("B", 1, 7, 4, minute = 55), habit("B", 1, 8, 6, minute = 5)), monday0745, 4)
        assertEquals(1, picks.size)
        assertEquals(6, picks.single().count)
    }

    @Test fun nextWarmDelayIsFiveMinutesBeforeUsualTime() {
        // usual 08:30, now 07:45 -> 45 min away, warm 5 min earlier
        assertEquals(40L, HabitPredictor.nextWarmDelayMinutes(listOf(habit("B", 1, 8, 3)), monday0745))
    }

    @Test fun nextWarmDelayWrapsToNextWeekAndIsNullWithoutHabits() {
        assertEquals(7 * 1440 - 5L - 0, HabitPredictor.nextWarmDelayMinutes(listOf(habit("B", 1, 7, 3, minute = 45)), monday0745))
        assertNull(HabitPredictor.nextWarmDelayMinutes(emptyList(), monday0745))
    }
}
