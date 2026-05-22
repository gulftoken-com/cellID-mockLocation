package com.example.celltrajectorymock.net

import android.util.Log
import com.example.celltrajectorymock.db.CellNode
import com.example.celltrajectorymock.telephony.CellSample
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Resolves a single cell tower to coordinates via the Google Geolocation API.
 *
 * This is the *online fallback* used only when the offline OpenCellID database
 * has no record of a cell. Google's cell database is far denser than the free
 * crowdsourced export, so it covers towers OpenCellID has never seen.
 *
 * It is one plain HTTPS POST — no Google Play Services, no SDK dependency. The
 * app stays offline-first: this path is reached only on a local-database miss.
 */
class GeolocationClient {

    /**
     * POSTs a single cell tower and returns its resolved location, or null if
     * the network failed, the key was rejected, or Google has no record either.
     */
    fun resolve(sample: CellSample, apiKey: String): CellNode? {
        val body = requestBody(sample).toString()
        return try {
            val connection =
                URL("$ENDPOINT?key=$apiKey").openConnection() as HttpsURLConnection
            connection.run {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // 404 = Google has no record of this cell either;
                    // 400/403 = the API key is missing, invalid or unauthorised.
                    Log.w(TAG, "geolocate HTTP $responseCode for ${sample.key()}")
                    disconnect()
                    return null
                }
                val text = inputStream.bufferedReader().use { it.readText() }
                disconnect()
                parse(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "geolocate failed for ${sample.key()}", e)
            null
        }
    }

    private fun requestBody(s: CellSample): JSONObject {
        val tower = JSONObject()
            .put("mobileCountryCode", s.mcc)
            .put("mobileNetworkCode", s.mnc)
            .put("locationAreaCode", s.lacTac)
        // 5G NR uses a 36-bit identity that travels in its own field.
        if (s.rat == "NR") tower.put("newRadioCellId", s.cid)
        else tower.put("cellId", s.cid)
        // Only forward a signal reading the modem actually populated — an
        // UNAVAILABLE placeholder would just be noise to the multilateration.
        if (s.dbm in PLAUSIBLE_DBM) tower.put("signalStrength", s.dbm)

        return JSONObject()
            .put("considerIp", false)          // cell-based only; never IP-guess
            .put("radioType", s.rat.lowercase())
            .put("cellTowers", JSONArray().put(tower))
    }

    private fun parse(json: String): CellNode? {
        val root = JSONObject(json)
        val loc = root.optJSONObject("location") ?: return null
        return CellNode(
            latitude = loc.getDouble("lat"),
            longitude = loc.getDouble("lng"),
            rangeMeters = root.optDouble("accuracy", DEFAULT_ACCURACY_M).toInt()
        )
    }

    companion object {
        private const val TAG = "GeolocationClient"
        private const val ENDPOINT =
            "https://www.googleapis.com/geolocation/v1/geolocate"
        private const val TIMEOUT_MS = 10_000
        private const val DEFAULT_ACCURACY_M = 3_000.0
        private val PLAUSIBLE_DBM = -150..-20
    }
}
