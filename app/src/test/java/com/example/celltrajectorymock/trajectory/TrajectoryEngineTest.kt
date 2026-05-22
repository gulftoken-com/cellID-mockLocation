package com.example.celltrajectorymock.trajectory

import com.example.celltrajectorymock.db.CellNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryEngineTest {

    private val eps = 1e-4

    @Test
    fun interpolate_atFractionZero_returnsPointA() {
        val (lat, lon) = TrajectoryEngine.interpolate(10.0, 20.0, 40.0, 50.0, 0.0)
        assertEquals(10.0, lat, eps)
        assertEquals(20.0, lon, eps)
    }

    @Test
    fun interpolate_atFractionOne_returnsPointB() {
        val (lat, lon) = TrajectoryEngine.interpolate(10.0, 20.0, 40.0, 50.0, 1.0)
        assertEquals(40.0, lat, eps)
        assertEquals(50.0, lon, eps)
    }

    @Test
    fun interpolate_midpointOnEquator_isHalfwayLongitude() {
        // Two equatorial points: the great-circle midpoint is exactly halfway.
        val (lat, lon) = TrajectoryEngine.interpolate(0.0, 0.0, 0.0, 10.0, 0.5)
        assertEquals(0.0, lat, eps)
        assertEquals(5.0, lon, eps)
    }

    @Test
    fun interpolate_midpointAtCommonLatitude_bowsTowardPole() {
        // A great circle between two same-latitude points bulges poleward, so
        // the midpoint latitude is slightly higher than the endpoints.
        val (lat, lon) = TrajectoryEngine.interpolate(50.0, 0.0, 50.0, 10.0, 0.5)
        assertEquals(5.0, lon, eps)
        assertTrue("expected lat > 50 but was $lat", lat > 50.0)
        assertTrue("expected lat < 50.2 but was $lat", lat < 50.2)
    }

    @Test
    fun interpolate_coincidentPoints_returnsThatPoint() {
        val (lat, lon) = TrajectoryEngine.interpolate(51.5, -0.12, 51.5, -0.12, 0.5)
        assertEquals(51.5, lat, eps)
        assertEquals(-0.12, lon, eps)
    }

    @Test
    fun rssFraction_equalSignals_isCentred() {
        assertEquals(0.5, TrajectoryEngine.rssFraction(-90, -90), eps)
    }

    @Test
    fun rssFraction_strongerB_biasesTowardB() {
        // -70 dBm is stronger than -110 dBm; fraction toward B must exceed 0.5.
        assertTrue(TrajectoryEngine.rssFraction(-110, -70) > 0.5)
    }

    @Test
    fun rssFraction_strongerA_biasesTowardA() {
        assertTrue(TrajectoryEngine.rssFraction(-70, -110) < 0.5)
    }

    @Test
    fun estimate_bothNull_returnsNull() {
        assertNull(TrajectoryEngine.estimate(null, null, null, null))
    }

    @Test
    fun estimate_onlyCurrent_returnsCurrentTower() {
        val fix = TrajectoryEngine.estimate(null, CellNode(51.5, -0.12, 900), null, -85)!!
        assertEquals(51.5, fix.latitude, eps)
        assertEquals(-0.12, fix.longitude, eps)
        assertEquals(900f, fix.accuracyMeters, 0.01f)
    }

    @Test
    fun estimate_onlyPrevious_returnsPreviousTower() {
        val fix = TrajectoryEngine.estimate(CellNode(48.85, 2.35, 1100), null, -85, null)!!
        assertEquals(48.85, fix.latitude, eps)
        assertEquals(2.35, fix.longitude, eps)
        assertEquals(1100f, fix.accuracyMeters, 0.01f)
    }

    @Test
    fun estimate_bothTowers_noSignal_usesMidpointAndAveragedAccuracy() {
        val fix = TrajectoryEngine.estimate(
            CellNode(0.0, 0.0, 800), CellNode(0.0, 10.0, 1200), null, null
        )!!
        assertEquals(0.0, fix.latitude, eps)
        assertEquals(5.0, fix.longitude, eps)
        assertEquals(1000f, fix.accuracyMeters, 0.01f)
    }

    @Test
    fun estimate_bothTowers_strongerCurrent_biasesTowardCurrent() {
        // Current tower (B) far stronger -> longitude pulled past the midpoint.
        val fix = TrajectoryEngine.estimate(
            CellNode(0.0, 0.0, 1000), CellNode(0.0, 10.0, 1000), -115, -65
        )!!
        assertTrue("expected lon > 5 but was ${fix.longitude}", fix.longitude > 5.0)
    }

    @Test
    fun distanceMeters_coincidentPoints_isZero() {
        assertEquals(0.0, TrajectoryEngine.distanceMeters(51.5, -0.12, 51.5, -0.12), eps)
    }

    @Test
    fun distanceMeters_oneDegreeOfLongitudeAtEquator_isAbout111km() {
        val d = TrajectoryEngine.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, d, 50.0)
    }

    @Test
    fun bearingDegrees_dueNorth_isZero() {
        assertEquals(0.0, TrajectoryEngine.bearingDegrees(0.0, 0.0, 1.0, 0.0), 1e-3)
    }

    @Test
    fun bearingDegrees_dueEast_is90() {
        assertEquals(90.0, TrajectoryEngine.bearingDegrees(0.0, 0.0, 0.0, 1.0), 1e-3)
    }

    @Test
    fun bearingDegrees_dueSouth_is180() {
        assertEquals(180.0, TrajectoryEngine.bearingDegrees(1.0, 0.0, 0.0, 0.0), 1e-3)
    }

    @Test
    fun bearingDegrees_dueWest_is270() {
        assertEquals(270.0, TrajectoryEngine.bearingDegrees(0.0, 1.0, 0.0, 0.0), 1e-3)
    }
}
