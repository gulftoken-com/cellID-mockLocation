package com.example.celltrajectorymock.persist

import android.content.Context
import com.example.celltrajectorymock.db.CellNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers cell towers resolved via the online API so each unknown cell costs
 * exactly one network request — ever. Backed by SharedPreferences so the cache
 * survives restarts; an in-memory map fronts it so hot lookups never touch disk.
 */
class ResolvedCellCache(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, CellNode>()

    /** Cached coordinates for [key] (a [com.example.celltrajectorymock.telephony.CellSample.key]). */
    fun get(key: String): CellNode? {
        memory[key]?.let { return it }
        val raw = prefs.getString(key, null) ?: return null
        return decode(raw)?.also { memory[key] = it }
    }

    fun put(key: String, node: CellNode) {
        memory[key] = node
        prefs.edit().putString(key, encode(node)).apply()
    }

    // Double.toString round-trips exactly, so a CSV line is a lossless record.
    private fun encode(n: CellNode): String =
        "${n.latitude},${n.longitude},${n.rangeMeters}"

    private fun decode(raw: String): CellNode? {
        val p = raw.split(',')
        if (p.size != 3) return null
        val lat = p[0].toDoubleOrNull() ?: return null
        val lon = p[1].toDoubleOrNull() ?: return null
        val range = p[2].toIntOrNull() ?: return null
        return CellNode(lat, lon, range)
    }

    companion object {
        private const val PREFS_NAME = "resolved_cells"
    }
}
