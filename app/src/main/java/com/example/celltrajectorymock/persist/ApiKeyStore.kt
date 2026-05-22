package com.example.celltrajectorymock.persist

import android.content.Context

/**
 * Stores the user's Google Geolocation API key for the online cell resolver.
 *
 * The key is entered on-device and kept in private SharedPreferences — it is
 * never compiled into the APK or committed to source.
 */
class ApiKeyStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved key, or null/blank when the user has not entered one. */
    var googleApiKey: String?
        get() = prefs.getString(KEY, null)
        set(value) {
            prefs.edit().putString(KEY, value?.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY = "google_geolocation_api_key"
    }
}
