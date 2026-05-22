package com.example.celltrajectorymock.trajectory

import com.example.celltrajectorymock.db.CellNode
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates a coarse position from two resolved cell-tower nodes.
 *
 * ACCURACY REALITY CHECK: the great-circle point between two transmitter masts
 * is NOT the user's true position and NOT the cell boundary. Expect error on
 * the order of the cell range itself — a few hundred metres in dense urban
 * areas, several kilometres rural. Every [Fix] is a zone, never a point; that
 * is why [Fix.accuracyMeters] is deliberately large.
 */
object TrajectoryEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float
    )

    /**
     * Great-circle interpolation between A and B.
     *
     * @param fraction 0.0 -> A, 1.0 -> B, 0.5 -> geometric midpoint.
     *
     * This is the general intermediate-point formula; with fraction = 0.5 it is
     * mathematically equivalent to the spec's two-step Haversine midpoint, but
     * it also supports signal-weighted blends (see [rssFraction]).
     */
    fun interpolate(
        latA: Double, lonA: Double,
        latB: Double, lonB: Double,
        fraction: Double
    ): Pair<Double, Double> {
        val phi1 = Math.toRadians(latA)
        val lam1 = Math.toRadians(lonA)
        val phi2 = Math.toRadians(latB)
        val lam2 = Math.toRadians(lonB)

        // Angular distance between the two nodes (haversine).
        val dPhi = phi2 - phi1
        val dLam = lam2 - lam1
        val h = sin(dPhi / 2).sq() +
            cos(phi1) * cos(phi2) * sin(dLam / 2).sq()
        val delta = 2 * asin(min(1.0, sqrt(h)))

        // Coincident / near-coincident nodes: the spherical formula divides by
        // sin(delta) and becomes unstable. A linear blend is indistinguishable
        // at this scale, so use it.
        if (delta < 1e-9) {
            return (latA + (latB - latA) * fraction) to
                (lonA + (lonB - lonA) * fraction)
        }

        val a = sin((1 - fraction) * delta) / sin(delta)
        val b = sin(fraction * delta) / sin(delta)

        val x = a * cos(phi1) * cos(lam1) + b * cos(phi2) * cos(lam2)
        val y = a * cos(phi1) * sin(lam1) + b * cos(phi2) * sin(lam2)
        val z = a * sin(phi1) + b * sin(phi2)

        val phiI = atan2(z, sqrt(x * x + y * y))
        val lamI = atan2(y, x)
        return Math.toDegrees(phiI) to Math.toDegrees(lamI)
    }

    /** Great-circle (haversine) distance between two points, in metres. */
    fun distanceMeters(
        latA: Double, lonA: Double,
        latB: Double, lonB: Double
    ): Double {
        val phi1 = Math.toRadians(latA)
        val phi2 = Math.toRadians(latB)
        val dPhi = Math.toRadians(latB - latA)
        val dLam = Math.toRadians(lonB - lonA)
        val h = sin(dPhi / 2).sq() +
            cos(phi1) * cos(phi2) * sin(dLam / 2).sq()
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /**
     * Initial great-circle bearing from A to B in degrees, normalised to
     * [0, 360). Returns 0.0 for coincident points (bearing is undefined there).
     */
    fun bearingDegrees(
        latA: Double, lonA: Double,
        latB: Double, lonB: Double
    ): Double {
        val phi1 = Math.toRadians(latA)
        val phi2 = Math.toRadians(latB)
        val dLam = Math.toRadians(lonB - lonA)
        val y = sin(dLam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Heuristic blend fraction from signal strength.
     *
     * dBm is negative and logarithmic, so the spec's `|P_A| / (|P_A| + |P_B|)`
     * is doubly wrong: a stronger signal has a SMALLER absolute value, and
     * linear weighting of a logarithmic quantity is not a distance ratio. Here
     * each reading is shifted above an assumed noise floor to a positive score
     * before normalising. This is still only a heuristic, not a calibrated
     * path-loss inversion — but it biases the fix toward the stronger tower in
     * the correct direction.
     *
     * @return fraction toward tower B in [0, 1].
     */
    fun rssFraction(dbmA: Int, dbmB: Int): Double {
        val noiseFloor = -140.0
        val scoreA = (dbmA - noiseFloor).coerceAtLeast(1.0)
        val scoreB = (dbmB - noiseFloor).coerceAtLeast(1.0)
        return scoreB / (scoreA + scoreB)
    }

    /**
     * Produces a [Fix] from the previous and current serving-cell nodes.
     * Degrades gracefully when only one tower resolves in the database, and
     * returns null when neither does.
     */
    fun estimate(
        previous: CellNode?,
        current: CellNode?,
        dbmPrevious: Int?,
        dbmCurrent: Int?
    ): Fix? {
        if (current == null && previous == null) return null
        if (current == null) {
            return Fix(
                previous!!.latitude, previous.longitude,
                previous.rangeMeters.toFloat()
            )
        }
        if (previous == null) {
            return Fix(
                current.latitude, current.longitude,
                current.rangeMeters.toFloat()
            )
        }

        val fraction =
            if (dbmPrevious != null && dbmCurrent != null) {
                rssFraction(dbmPrevious, dbmCurrent)
            } else {
                0.5
            }
        val (lat, lon) = interpolate(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
            fraction
        )
        val accuracy = (previous.rangeMeters + current.rangeMeters) / 2f
        return Fix(lat, lon, accuracy)
    }

    private fun Double.sq() = this * this
}
