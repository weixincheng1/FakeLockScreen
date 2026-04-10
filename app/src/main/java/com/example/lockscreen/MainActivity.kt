package com.example.lockscreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private lateinit var textBallSizeValue: TextView
    private lateinit var seekBallSize: SeekBar

    private var rootAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)
        textBallSizeValue = findViewById(R.id.textBallSizeValue)
        seekBallSize = findViewById(R.id.seekBallSize)

        val btnGrantPermission: Button = findViewById(R.id.btnGrantPermission)
        val btnGrantAccessibility: Button = findViewById(R.id.btnGrantAccessibility)
        val btnStartService: Button = findViewById(R.id.btnStartService)
        val btnStopService: Button = findViewById(R.id.btnStopService)

        rootAvailable = RootShell.isRootAvailable()
        initBallSizeSeekBar()

        btnGrantPermission.setOnClickListener {
            openOverlayPermissionPage()
        }
        btnGrantAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        btnStartService.setOnClickListener {
            startServiceWithPermissions()
        }
        btnStopService.setOnClickListener {
            stopFloatingService()
        }
    }

    override fun onResume() {
        super.onResume()
        rootAvailable = RootShell.isRootAvailable(forceCheck = true)
        updatePermissionStatus()
        refreshBallSizeText(getSavedBallSizeDp())
    }

    private fun startServiceWithPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_required, Toast.LENGTH_SHORT).show()
            openOverlayPermissionPage()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        startFloatingService()

        if (!isVolumeAccessibilityEnabled()) {
            Toast.makeText(
                this,
                R.string.toast_accessibility_recommended,
                Toast.LENGTH_LONG
            ).show()
        }
        if (!rootAvailable) {
            Toast.makeText(this, R.string.toast_root_not_available, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_START
            putExtra(FloatingService.EXTRA_FLOAT_SIZE_DP, getSavedBallSizeDp())
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.toast_service_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, R.string.toast_service_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun openOverlayPermissionPage() {
        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updatePermissionStatus() {
        val overlayText = if (Settings.canDrawOverlays(this)) {
            getString(R.string.status_item_enabled)
        } else {
            getString(R.string.status_item_disabled)
        }

        val accessibilityText = if (isVolumeAccessibilityEnabled()) {
            getString(R.string.status_item_enabled)
        } else {
            getString(R.string.status_item_disabled)
        }

        val rootText = if (rootAvailable) {
            getString(R.string.status_item_enabled)
        } else {
            getString(R.string.status_item_disabled)
        }

        textStatus.text = getString(
            R.string.status_permission_summary,
            overlayText,
            accessibilityText,
            rootText
        )
    }

    private fun initBallSizeSeekBar() {
        seekBallSize.max = AppSettings.MAX_FLOAT_SIZE_DP - AppSettings.MIN_FLOAT_SIZE_DP
        val savedSizeDp = getSavedBallSizeDp()
        seekBallSize.progress = savedSizeDp - AppSettings.MIN_FLOAT_SIZE_DP
        refreshBallSizeText(savedSizeDp)

        seekBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + AppSettings.MIN_FLOAT_SIZE_DP
                refreshBallSizeText(size)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val size = (seekBar?.progress ?: 0) + AppSettings.MIN_FLOAT_SIZE_DP
                saveBallSizeDp(size)
                refreshBallSizeText(size)
                updateSizeIfServiceRunning(size)
            }
        })
    }

    private fun updateSizeIfServiceRunning(sizeDp: Int) {
        if (!FloatingService.isRunning) {
            return
        }

        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_UPDATE_BALL_SIZE
            putExtra(FloatingService.EXTRA_FLOAT_SIZE_DP, sizeDp)
        }
        startService(intent)
    }

    private fun getSavedBallSizeDp(): Int {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val size = prefs.getInt(AppSettings.KEY_FLOAT_SIZE_DP, AppSettings.DEFAULT_FLOAT_SIZE_DP)
        return size.coerceIn(AppSettings.MIN_FLOAT_SIZE_DP, AppSettings.MAX_FLOAT_SIZE_DP)
    }

    private fun saveBallSizeDp(sizeDp: Int) {
        getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(AppSettings.KEY_FLOAT_SIZE_DP, sizeDp)
            .apply()
    }

    private fun refreshBallSizeText(sizeDp: Int) {
        textBallSizeValue.text = getString(R.string.label_ball_size_value, sizeDp)
    }

    private fun isVolumeAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceId = "$packageName/${VolumeKeyAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.equals(serviceId, ignoreCase = true) }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}
