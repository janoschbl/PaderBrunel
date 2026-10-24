package dev.padermodern.data

import org.junit.Assert.*
import org.junit.Test

class JourneyProgressTest {
    private val stops = listOf(
        Station(id = "a", name = "Start", latitude = 51.7, longitude = 8.75),
        Station(id = "b", name = "Detmolder Tor", platform = "1", latitude = 51.705, longitude = 8.75),
        Station(id = "c", name = "Cheruskerstr.", latitude = 51.71, longitude = 8.75),
    )
    private fun fix(lat: Double, elapsed: Long, accuracy: Float = 10f, age: Long = 0, speed: Float? = 0f) =
        JourneyFix(lat, 8.75, accuracy, age, elapsed, speed)
    private fun acquire(engine: JourneyProgress, index: Int, elapsed: Long = 0) {
        assertNull(engine.update(fix(stops[index].latitude, elapsed), 0))
        engine.update(fix(stops[index].latitude, elapsed + 4_000), 0)
    }

    @Test fun announcesNextOnceAfterLeavingConfirmedStop() {
        val engine = JourneyProgress(stops)
        acquire(engine, 0)
        val next = engine.update(fix(51.702, 8_000), 0)
        assertEquals("next:1", next?.id)
        assertTrue(next!!.text.contains("Detmolder Tor, Steig 1"))
        assertNull(engine.update(fix(51.7022, 12_000), 0))
    }

    @Test fun warnsOnPreviousStopDepartureNotAtDestination() {
        val engine = JourneyProgress(stops)
        acquire(engine, 1)
        val next = engine.update(fix(51.707, 8_000), 0)
        assertTrue(next!!.text.contains("Hier steigst du aus"))
        assertTrue(next.text.contains("Cheruskerstraße"))
        assertFalse(engine.arrived)
    }

    @Test fun arrivalRequiresTwoDistinctSlowFixes() {
        val engine = JourneyProgress(stops)
        assertNull(engine.update(fix(51.71, 0), 0))
        assertNull(engine.update(fix(51.71, 0), 0))
        assertFalse(engine.arrived)
        val arrival = engine.update(fix(51.71, 4_000), 0)
        assertEquals("arrived:2", arrival?.id)
        assertTrue(engine.arrived)
        assertNull(engine.update(fix(51.71, 8_000), 0))
    }

    @Test fun passingDestinationAtSpeedIsNotArrival() {
        val engine = JourneyProgress(stops)
        engine.update(fix(51.71, 0, speed = 10f), 0)
        assertNull(engine.update(fix(51.71, 4_000, speed = 10f), 0))
        assertFalse(engine.arrived)
    }

    @Test fun unknownSpeedDoesNotProveArrival() {
        val engine = JourneyProgress(stops)
        engine.update(fix(51.71, 0, speed = null), 0)
        assertNull(engine.update(fix(51.71, 4_000, speed = null), 0))
        assertFalse(engine.arrived)
    }

    @Test fun staleOrInaccurateLocationsCannotAcquireAStop() {
        val engine = JourneyProgress(stops)
        engine.update(fix(51.7, 0), 0)
        engine.update(fix(51.7, 4_000, age = 31_000), 0)
        engine.update(fix(51.7, 8_000, accuracy = 100f), 0)
        assertNull(engine.nextStopIndex)
        assertFalse(engine.hasGoodFix)
    }

    @Test fun futureOrInvalidFixIsRejected() {
        for (bad in listOf(fix(51.7, 0, age = -1), fix(Double.NaN, 0), fix(91.0, 0), fix(51.7, 0, accuracy = Float.NaN))) {
            val engine = JourneyProgress(stops)
            engine.update(bad, 0)
            assertFalse(engine.hasGoodFix)
        }
    }

    @Test fun nearbyUnrelatedPositionDoesNotInventProgress() {
        val engine = JourneyProgress(stops)
        engine.update(fix(51.75, 0), 0)
        engine.update(fix(51.75, 4_000), 0)
        assertNull(engine.nextStopIndex)
    }

    @Test fun noBacktrackingWhenGpsJumpsToPreviousStop() {
        val engine = JourneyProgress(stops)
        acquire(engine, 1)
        engine.update(fix(51.7, 8_000), 0)
        engine.update(fix(51.7, 12_000), 0)
        assertEquals(2, engine.nextStopIndex)
    }

    @Test fun missingStopCoordinatesAreNotDeviceCoordinates() {
        val engine = JourneyProgress(stops.map { it.copy(latitude = 0.0, longitude = 0.0) })
        engine.update(fix(51.7, 0), 0)
        engine.update(fix(51.7, 4_000), 0)
        assertNull(engine.nextStopIndex)
    }

    @Test fun initialStopMustBePlausibleForJourneyTime() {
        val engine = JourneyProgress(stops.map { it.copy(date = "2026-10-23T10:00:00+0200") })
        val now = parseTime("2026-10-23T15:00:00+0200")!!.toInstant().toEpochMilli()
        engine.update(fix(51.7, 0), now)
        engine.update(fix(51.7, 4_000), now)
        assertNull(engine.nextStopIndex)
    }

    @Test fun platformPrefixIsNotDuplicated() {
        assertEquals("Detmolder Tor, Steig 1", JourneyProgress.spoken(stops[1].copy(platform = "Steig 1")))
        assertEquals("Detmolder Tor, Gleis 2", JourneyProgress.spoken(stops[1].copy(platform = "Gleis 2")))
    }

    @Test fun refreshedDelayIsUsedDuringInitialStopAcquisition() {
        val old = stops.map { it.copy(date = "2026-10-23T10:00:00+0200") }
        val engine = JourneyProgress(old)
        val now = parseTime("2026-10-23T11:00:00+0200")!!.toInstant().toEpochMilli()
        engine.refreshStops(old.map { it.copy(expectedDate = "2026-10-23T11:00:00+0200") })
        engine.update(fix(51.7, 0), now)
        engine.update(fix(51.7, 4_000), now)
        assertEquals(1, engine.nextStopIndex)
    }

    @Test fun reacquisitionSkipsMissedStopsWithoutPlayingBacklog() {
        val engine = JourneyProgress(stops)
        acquire(engine, 0)
        engine.update(fix(51.704, 8_000, age = 45_000), 0)
        engine.update(fix(51.71, 12_000), 0)
        val arrival = engine.update(fix(51.71, 16_000), 0)
        assertEquals("arrived:2", arrival?.id)
        assertTrue(engine.arrived)
    }

    @Test fun matchedBusCanAdvanceButNeverProveArrival() {
        val engine = JourneyProgress(stops)
        acquire(engine, 0)
        val announcement = engine.confirmBusNextStop(2)
        assertEquals("next:2", announcement?.id)
        assertTrue(announcement!!.text.contains("Hier steigst du aus"))
        assertFalse(engine.arrived)
        assertNull(engine.confirmBusNextStop(2))
        assertNull(engine.confirmBusNextStop(1))
        assertNull(engine.confirmBusNextStop(3))
    }

    @Test fun busCannotInventInitialProgressOrCanceledStop() {
        val engine = JourneyProgress(stops)
        assertNull(engine.confirmBusNextStop(1))
        acquire(engine, 0)
        engine.refreshStops(stops.mapIndexed { index, stop -> stop.copy(canceled = index == 2) })
        assertNull(engine.confirmBusNextStop(2))
    }
}
