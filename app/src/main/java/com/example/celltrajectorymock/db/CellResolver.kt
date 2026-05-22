package com.example.celltrajectorymock.db

import android.util.Log
import com.example.celltrajectorymock.net.GeolocationClient
import com.example.celltrajectorymock.persist.ResolvedCellCache
import com.example.celltrajectorymock.telephony.CellSample
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a [CellSample] into coordinates, trying the cheapest source first:
 *
 *   1. the offline OpenCellID database        — instant, free
 *   2. the on-device cache of past online hits — instant, free
 *   3. the Google Geolocation API              — network, costs a quota unit
 *
 * A cell that even Google cannot place is remembered for the session, so the
 * network is not retried on every handover. The online steps are skipped
 * entirely when no API key has been entered, leaving a pure-offline app.
 */
class CellResolver(
    private val database: CellDatabase,
    private val cache: ResolvedCellCache,
    private val online: GeolocationClient,
    private val apiKeyProvider: () -> String?
) {
    /** A resolved tower, tagged with whether the online API was consulted. */
    data class Resolved(val node: CellNode, val fromOnline: Boolean)

    private val unresolvable = ConcurrentHashMap.newKeySet<String>()

    fun resolve(sample: CellSample): Resolved? {
        database.lookup(sample.mcc, sample.mnc, sample.lacTac, sample.cid)
            ?.let { return Resolved(it, fromOnline = false) }

        val key = sample.key()
        cache.get(key)?.let { return Resolved(it, fromOnline = true) }
        if (key in unresolvable) return null

        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: return null
        val node = online.resolve(sample, apiKey)
        if (node == null) {
            // Neither database has it; do not hammer the API again this session.
            unresolvable += key
            return null
        }
        cache.put(key, node)
        Log.i(TAG, "resolved $key online -> ${node.latitude},${node.longitude}")
        return Resolved(node, fromOnline = true)
    }

    companion object {
        private const val TAG = "CellResolver"
    }
}
