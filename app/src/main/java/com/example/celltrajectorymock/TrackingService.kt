package com.example.celltrajectorymock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.example.celltrajectorymock.db.CellDatabase
import com.example.celltrajectorymock.db.CellResolver
import com.example.celltrajectorymock.mock.MockInjectionFabric
import com.example.celltrajectorymock.net.GeolocationClient
import com.example.celltrajectorymock.persist.ApiKeyStore
import com.example.celltrajectorymock.persist.LastFixStore
import com.example.celltrajectorymock.persist.ResolvedCellCache
import com.example.celltrajectorymock.state.HandoverStateMachine
import com.example.celltrajectorymock.telephony.CellInfoCollector
import com.example.celltrajectorymock.telephony.CellSample
import com.example.celltrajectorymock.trajectory.TrajectoryEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Persistent foreground service that wires the pipeline together:
 *
 *   CellInfoCollector -> HandoverStateMachine -> CellDatabase
 *                     -> TrajectoryEngine -> MockInjectionFabric
 *
 * Runs as a foreground service of type "location" so the Low Memory Killer
 * leaves it alone during long screen-off periods. The last fix is persisted
 * via [LastFixStore] so it can be re-injected immediately after an LMK restart.
 */
class TrackingService : Service() {

    // Background-only: nothing here touches the UI thread (HLR-NF-202).
    private val worker = Executors.newSingleThreadExecutor()
    private var repushExecutor: ScheduledExecutorService? = null

    private lateinit var database: CellDatabase
    private lateinit var resolver: CellResolver
    private lateinit var mock: MockInjectionFabric
    private lateinit var collector: CellInfoCollector
    private lateinit var stateMachine: HandoverStateMachine
    private lateinit var fixStore: LastFixStore

    @Volatile private var lastFix: TrajectoryEngine.Fix? = null
    @Volatile private var lastFixTimeMillis: Long = 0L
    @Volatile private var lastSpeedMps: Float = 0f
    @Volatile private var lastBearingDeg: Float = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within ~5s of startForegroundService().
        startForeground(
            NOTIF_ID,
            buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        worker.execute(::initPipeline)
        return START_STICKY
    }

    private fun initPipeline() {
        fixStore = LastFixStore(this)

        database = CellDatabase(this)
        runCatching { database.open() }.onFailure { e ->
            Log.e(TAG, "cells.db open failed", e)
            publish("ERROR: cells.db unavailable — see assets/cells.db")
        }

        // Offline DB first; the online Geolocation API is a fallback used only
        // for towers OpenCellID has never seen, and only if a key was entered.
        val apiKeyStore = ApiKeyStore(this)
        resolver = CellResolver(
            database = database,
            cache = ResolvedCellCache(this),
            online = GeolocationClient()
        ) { apiKeyStore.googleApiKey }

        mock = MockInjectionFabric(this)
        if (mock.setUp().isFailure) {
            publish("ERROR: not the selected mock app — open Developer Options")
        } else {
            // After an LMK restart, re-inject the last known fix immediately so
            // consumers are not left dark until the next handover arrives.
            val restored = fixStore.load()
            if (restored != null) {
                lastFix = restored
                lastFixTimeMillis = System.currentTimeMillis()
                mock.push(
                    restored.latitude, restored.longitude,
                    restored.accuracyMeters, 0f, 0f
                )
                publish("Restored last fix — waiting for next handover")
            } else {
                publish("Running — waiting for first cell lock")
            }
        }

        stateMachine = HandoverStateMachine(onValidHandover = ::onValidHandover)
        collector = CellInfoCollector(this) { sample ->
            // Invoked on the collector's worker thread.
            stateMachine.onSample(sample)
        }
        collector.start()

        // Re-inject the last fix periodically so consumer apps keep seeing it
        // as a live, fresh location between (infrequent) handover events.
        repushExecutor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay(
                ::repush, REPUSH_SECONDS, REPUSH_SECONDS, TimeUnit.SECONDS
            )
        }
    }

    private fun onValidHandover(previous: CellSample?, current: CellSample) {
        // resolve() may block briefly on a network call; this runs on the
        // collector's worker thread, never the UI thread.
        val previousResolved = previous?.let { resolver.resolve(it) }
        val currentResolved = resolver.resolve(current)

        val fix = TrajectoryEngine.estimate(
            previous = previousResolved?.node,
            current = currentResolved?.node,
            dbmPrevious = previous?.dbm,
            dbmCurrent = current.dbm
        )
        if (fix == null) {
            publish("Handover ${current.key()} — tower not found (offline DB or online)")
            return
        }

        // Derive speed and bearing from the displacement since the last fix.
        val now = System.currentTimeMillis()
        val priorFix = lastFix
        val priorTime = lastFixTimeMillis
        if (priorFix != null && priorTime > 0L && now > priorTime) {
            val elapsedSeconds = (now - priorTime) / 1000.0
            val metres = TrajectoryEngine.distanceMeters(
                priorFix.latitude, priorFix.longitude,
                fix.latitude, fix.longitude
            )
            lastSpeedMps =
                (metres / elapsedSeconds).coerceIn(0.0, MAX_SPEED_MPS).toFloat()
            lastBearingDeg = TrajectoryEngine.bearingDegrees(
                priorFix.latitude, priorFix.longitude,
                fix.latitude, fix.longitude
            ).toFloat()
        }

        lastFix = fix
        lastFixTimeMillis = now
        fixStore.save(fix)
        mock.push(
            fix.latitude, fix.longitude, fix.accuracyMeters,
            lastSpeedMps, lastBearingDeg
        )
        val source = if (currentResolved?.fromOnline == true) " online" else ""
        publish(
            "Fix %.5f, %.5f  ±%.0fm  %.0fm/s  %.0f°  [%s]%s".format(
                fix.latitude, fix.longitude, fix.accuracyMeters,
                lastSpeedMps, lastBearingDeg, current.key(), source
            )
        )
    }

    /** Re-pushes the last fix with a refreshed timestamp; speed resets to 0. */
    private fun repush() {
        val fix = lastFix ?: return
        mock.push(fix.latitude, fix.longitude, fix.accuracyMeters, 0f, lastBearingDeg)
    }

    override fun onDestroy() {
        runCatching { if (::collector.isInitialized) collector.stop() }
        repushExecutor?.shutdownNow()
        runCatching { if (::mock.isInitialized) mock.tearDown() }
        runCatching { if (::database.isInitialized) database.close() }
        worker.shutdownNow()
        publish("Stopped")
        super.onDestroy()
    }

    // --- Notification -------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cell Trajectory Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Persistent cell-based mock location service" }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CellTrajectoryMock")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

    private fun publish(status: String) {
        state.value = status
        runCatching { notificationManager().notify(NOTIF_ID, buildNotification(status)) }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "cell_trajectory"
        private const val NOTIF_ID = 1001
        private const val REPUSH_SECONDS = 10L
        private const val MAX_SPEED_MPS = 100.0   // clamp obvious noise spikes

        /** Latest human-readable status, observed by [MainActivity]. */
        private val state = MutableStateFlow("Idle")
        val status: StateFlow<String> get() = state
    }
}
