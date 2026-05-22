package com.example.celltrajectorymock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.example.celltrajectorymock.persist.ApiKeyStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Single-screen control panel: grant permissions, jump to Developer Options to
 * be selected as the mock location app, then start/stop the tracking service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var apiKeyStore: ApiKeyStore

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        statusView.text = if (hasAllPermissions()) {
            getString(R.string.hint_after_permissions)
        } else {
            getString(R.string.hint_missing_permissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
        findViewById<Button>(R.id.btn_dev_options).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startTracking() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopTracking() }

        apiKeyStore = ApiKeyStore(this)
        val apiKeyField = findViewById<EditText>(R.id.api_key)
        apiKeyField.setText(apiKeyStore.googleApiKey.orEmpty())
        findViewById<Button>(R.id.btn_save_key).setOnClickListener {
            val entered = apiKeyField.text.toString().trim()
            apiKeyStore.googleApiKey = entered
            statusView.text = getString(
                if (entered.isEmpty()) R.string.api_key_cleared
                else R.string.api_key_saved
            )
        }

        // Mirror the service status into the UI while this screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingService.status.collect { statusView.text = it }
            }
        }
    }

    private fun startTracking() {
        if (!hasAllPermissions()) {
            statusView.text = getString(R.string.hint_grant_first)
            permissionLauncher.launch(requiredPermissions)
            return
        }
        ContextCompat.startForegroundService(
            this, Intent(this, TrackingService::class.java)
        )
    }

    private fun stopTracking() {
        stopService(Intent(this, TrackingService::class.java))
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
