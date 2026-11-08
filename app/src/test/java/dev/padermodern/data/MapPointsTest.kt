package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MapPointsTest {
    @Test fun stopsWithoutCoordinatesDoNotStretchTheMapToTheGulfOfGuinea() {
        val pts = listOf(51.96 to 7.63, 0.0 to 0.0, 51.71 to 8.74, Double.NaN to 8.0)
        assertEquals(listOf(51.96 to 7.63, 51.71 to 8.74), pts.positioned())
    }
}
