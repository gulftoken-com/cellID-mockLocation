package com.example.celltrajectorymock.persist

import android.content.Context
import com.example.celltrajectorymock.trajectory.TrajectoryEngine

/**
 * Persists the most recent fix so it survives a process restart.
 *
 * The service runs as START_STICKY: after the Low Memory Killer ends it, the OS
 * restarts it. Without persistence the app would go dark until the next cell
 * handover. Reloading the last fix lets it re-inject a position immediately.
 *
 * Latitude/longitude are stored as raw IEEE-754 bits because SharedPreferences
 * has no double accessor and float precision is too coarse for coordinates.
 */
class LastFixStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(fix: TrajectoryEngine.Fix) {
        prefs.edit()
            .putLong(KEY_LAT, fix.latitude.toRawBits())
            .putLong(KEY_LON, fix.longitude.toRawBits())
            .putFloat(KEY_ACC, fix.accuracyMeters)
            .putBoolean(KEY_PRESENT, true)
            .apply()
    }

    fun load(): TrajectoryEngine.Fix? {
        if (!prefs.getBoolean(KEY_PRESENT, false)) return null
        return TrajectoryEngine.Fix(
            latitude = Double.fromBits(prefs.getLong(KEY_LAT, 0L)),
            longitude = Double.fromBits(prefs.getLong(KEY_LON, 0L)),
            accuracyMeters = prefs.getFloat(KEY_ACC, DEFAULT_ACCURACY_M)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "last_fix"
        private const val KEY_PRESENT = "present"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_ACC = "acc"
        private const val DEFAULT_ACCURACY_M = 1_000f
    }
}
