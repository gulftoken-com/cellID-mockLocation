package com.example.celltrajectorymock.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Subscribes to baseband [CellInfo] callbacks and emits the registered serving
 * cell as a [CellSample]. GSM (2G), WCDMA/UMTS (3G), LTE (4G) and NR (5G) are
 * all resolved — the lower RATs matter because a device in weak coverage (the
 * exact GPS-denied scenario this app targets) frequently drops to 3G or 2G.
 *
 * REALITY CHECK: Android throttles CellInfo delivery aggressively. Expect
 * updates on the order of tens of seconds — not milliseconds — especially with
 * the screen off. [TelephonyManager.requestCellInfoUpdate] is used to nudge the
 * modem on a timer, but it is itself rate-limited by the framework. The
 * "millisecond ping-pong" the handover state machine guards against will mostly
 * be smoothed away by the OS before it ever reaches this callback.
 */
class CellInfoCollector(
    private val context: Context,
    private val onSample: (CellSample) -> Unit
) {
    private val telephony =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // Single worker thread keeps all radio parsing off the main UI thread.
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()
    private val nudgeExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private var callback: CellCallback? = null

    fun start() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; cannot read CellInfo")
            return
        }
        val cb = CellCallback()
        callback = cb
        telephony.registerTelephonyCallback(callbackExecutor, cb)
        nudgeExecutor.scheduleWithFixedDelay(
            ::requestUpdate, 0L, NUDGE_SECONDS, TimeUnit.SECONDS
        )
    }

    fun stop() {
        callback?.let { telephony.unregisterTelephonyCallback(it) }
        callback = null
        nudgeExecutor.shutdownNow()
    }

    /** Asks the modem for a fresh scan; the framework may ignore it if throttled. */
    private fun requestUpdate() {
        if (!hasLocationPermission()) return
        try {
            telephony.requestCellInfoUpdate(
                callbackExecutor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        dispatch(cellInfo)
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "requestCellInfoUpdate denied", e)
        }
    }

    private fun dispatch(cells: List<CellInfo>) {
        // Screen out transient non-serving neighbour towers; keep the first
        // registered cell this build understands — the serving connection.
        val sample = cells.asSequence()
            .filter { it.isRegistered }
            .mapNotNull(::toSample)
            .firstOrNull() ?: return
        onSample(sample)
    }

    private fun toSample(info: CellInfo): CellSample? {
        val now = System.currentTimeMillis()
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: return null
                val mnc = id.mncString?.toIntOrNull() ?: return null
                val tac = id.tac
                val ci = id.ci   // CellIdentityLte.getCi() — getCid() was removed
                if (tac == CellInfo.UNAVAILABLE || ci == CellInfo.UNAVAILABLE) return null
                CellSample(
                    rat = "LTE",
                    mcc = mcc, mnc = mnc, lacTac = tac, cid = ci.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    timestampMillis = now
                )
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as? CellIdentityNr ?: return null
                val mcc = id.mccString?.toIntOrNull() ?: return null
                val mnc = id.mncString?.toIntOrNull() ?: return null
                val tac = id.tac
                val nci = id.nci   // 36-bit New Radio identity
                if (tac == CellInfo.UNAVAILABLE || nci == CellInfo.UNAVAILABLE_LONG) {
                    return null
                }
                CellSample(
                    rat = "NR",
                    mcc = mcc, mnc = mnc, lacTac = tac, cid = nci,
                    dbm = info.cellSignalStrength.dbm,
                    timestampMillis = now
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: return null
                val mnc = id.mncString?.toIntOrNull() ?: return null
                val lac = id.lac
                val cid = id.cid   // UMTS Cell Identity
                if (lac == CellInfo.UNAVAILABLE || cid == CellInfo.UNAVAILABLE) return null
                CellSample(
                    rat = "WCDMA",
                    mcc = mcc, mnc = mnc, lacTac = lac, cid = cid.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    timestampMillis = now
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: return null
                val mnc = id.mncString?.toIntOrNull() ?: return null
                val lac = id.lac
                val cid = id.cid   // GSM Cell Identity
                if (lac == CellInfo.UNAVAILABLE || cid == CellInfo.UNAVAILABLE) return null
                CellSample(
                    rat = "GSM",
                    mcc = mcc, mnc = mnc, lacTac = lac, cid = cid.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    timestampMillis = now
                )
            }
            else -> null   // CDMA (retired on modern networks) is not resolved
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private inner class CellCallback :
        TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            dispatch(cellInfo)
        }
    }

    companion object {
        private const val TAG = "CellInfoCollector"
        private const val NUDGE_SECONDS = 15L
    }
}
