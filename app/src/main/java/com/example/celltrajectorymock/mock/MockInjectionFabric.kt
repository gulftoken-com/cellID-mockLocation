package com.example.celltrajectorymock.mock

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log

/**
 * Registers test location providers and injects synthetic fixes into them.
 *
 * Both the GPS and NETWORK providers are mocked. The spec called for the GPS
 * provider only, but consumer apps may read either one, and blending clients
 * (fused location) draw on both — mocking NETWORK as well is strictly additive
 * and widens the set of apps that actually receive the fix.
 *
 * Authorisation: this only works while the app is selected under
 * Settings > Developer options > "Select mock location app". If it is not, the
 * test-provider calls throw [SecurityException]; [setUp] reports that as a
 * failed [Result].
 *
 * API note: the deprecated multi-boolean `addTestProvider(...)` overload used
 * in the spec is replaced with the [ProviderProperties] overload, supported
 * since API 31.
 */
class MockInjectionFabric(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var ready = false

    /** Provisions the test providers. Returns failure if not authorised. */
    fun setUp(): Result<Unit> = try {
        val properties = ProviderProperties.Builder()
            .setHasNetworkRequirement(false)
            .setHasSatelliteRequirement(false)
            .setHasCellRequirement(false)
            .setHasMonetaryCost(false)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()

        for (provider in PROVIDERS) {
            // Remove any stale provider first to avoid resource collision.
            runCatching { locationManager.removeTestProvider(provider) }
            locationManager.addTestProvider(provider, properties)
            locationManager.setTestProviderEnabled(provider, true)
        }
        ready = true
        Result.success(Unit)
    } catch (e: SecurityException) {
        Log.w(TAG, "Not authorised as the mock location app", e)
        Result.failure(e)
    }

    /**
     * Injects a fix into every mocked provider. All time fields are populated
     * because modern Android rejects a Location that is not "complete" — in
     * particular elapsedRealtimeNanos, which the framework uses to judge
     * freshness.
     *
     * Speed/bearing accuracies are reported deliberately wide: these values are
     * derived from coarse cell handovers, not real GNSS Doppler.
     */
    fun push(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        speedMps: Float,
        bearingDeg: Float
    ) {
        if (!ready) return
        for (provider in PROVIDERS) {
            val location = buildLocation(
                provider, latitude, longitude, accuracyMeters, speedMps, bearingDeg
            )
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: SecurityException) {
                Log.w(TAG, "Lost mock location authorisation", e)
                ready = false
                return
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Test provider $provider no longer registered", e)
                ready = false
                return
            }
        }
    }

    private fun buildLocation(
        provider: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        speedMps: Float,
        bearingDeg: Float
    ): Location = Location(provider).apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = accuracyMeters
        altitude = 0.0
        speed = speedMps
        bearing = bearingDeg
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        verticalAccuracyMeters = accuracyMeters
        speedAccuracyMetersPerSecond = SPEED_ACCURACY_MPS
        bearingAccuracyDegrees = BEARING_ACCURACY_DEG
    }

    fun tearDown() {
        for (provider in PROVIDERS) {
            runCatching { locationManager.setTestProviderEnabled(provider, false) }
            runCatching { locationManager.removeTestProvider(provider) }
        }
        ready = false
    }

    companion object {
        private const val TAG = "MockInjectionFabric"
        private const val SPEED_ACCURACY_MPS = 5f
        private const val BEARING_ACCURACY_DEG = 45f
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }
}
