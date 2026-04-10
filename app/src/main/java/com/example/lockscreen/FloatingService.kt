package com.example.lockscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingService : Service() {

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }
    private val touchSlop by lazy {
        ViewConfiguration.get(this).scaledTouchSlop
    }

    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var longPressRunnable: Runnable? = null
    private var ballSizeDp = AppSettings.DEFAULT_FLOAT_SIZE_DP

    private var backlightModeApplied = false
    private var brightnessModeBackup: String? = null
    private var brightnessValueBackup: String? = null
    private var backlightPathValueBackup: MutableMap<String, String> = linkedMapOf()
    private var blPowerPathValueBackup: MutableMap<String, String> = linkedMapOf()
    private var fbBlankPathValueBackup: MutableMap<String, String> = linkedMapOf()
    private var backlightEnforceRunnable: Runnable? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var lastScreenOffWakeAtMs = 0L
    private var screenOffWakePending = false
    private var clearWakePendingRunnable: Runnable? = null
    private val secureSettingsBackup: MutableMap<String, String> = linkedMapOf()
    private var noInstantLockPolicyApplied = false
    @Volatile
    private var lanServerRunning = false
    private var lanServerSocket: ServerSocket? = null
    private var lanServerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopServiceSafely()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (floatingView == null) {
            addFloatingBall()
        }

        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                updateFloatingBallSize(getSavedBallSizeDp())
                syncLanControlServerState()
            }

            ACTION_UPDATE_BALL_SIZE -> {
                updateFloatingBallSize(
                    intent?.getIntExtra(EXTRA_FLOAT_SIZE_DP, getSavedBallSizeDp())
                        ?: getSavedBallSizeDp()
                )
            }

            ACTION_EXIT_BLACK_SCREEN -> {
                exitBlackScreen()
            }

            ACTION_ENTER_BLACK_SCREEN -> {
                enterBlackScreen()
            }

            ACTION_SYNC_LAN_CONTROL -> {
                syncLanControlServerState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        longPressRunnable?.let(mainHandler::removeCallbacks)
        isBlackModeOn = false
        stopBacklightEnforceLoop()
        clearScreenOffWakePending()
        unregisterScreenStateReceiver()
        stopLanControlServer()
        restoreNoInstantLockPolicyIfNeeded()
        restoreBacklightIfNeeded()
        removeFloatingBall()
        isRunning = false
        super.onDestroy()
    }

    private fun addFloatingBall() {
        ballSizeDp = getSavedBallSizeDp()

        val params = WindowManager.LayoutParams(
            ballSizeDp.dp,
            ballSizeDp.dp,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80.dp
            y = 220.dp
        }

        val ballView = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@FloatingService, R.drawable.bg_floating_ball)
            alpha = 0.92f
        }

        attachDragAndLongPress(ballView, params)
        windowManager.addView(ballView, params)
        floatingView = ballView
        floatingParams = params
    }

    private fun attachDragAndLongPress(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y

                        longPressRunnable?.let(mainHandler::removeCallbacks)
                        longPressRunnable = Runnable {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            enterBlackScreen()
                        }
                        mainHandler.postDelayed(
                            longPressRunnable!!,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY

                        if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                            longPressRunnable?.let(mainHandler::removeCallbacks)
                            params.x = startX + deltaX.toInt()
                            params.y = startY + deltaY.toInt()
                            floatingView?.let { windowManager.updateViewLayout(it, params) }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let(mainHandler::removeCallbacks)
                        longPressRunnable = null
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun enterBlackScreen() {
        if (isBlackModeOn) {
            return
        }
        floatingView?.visibility = View.GONE

        if (!RootShell.isRootAvailable(forceCheck = true)) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        if (applyBacklightOffIfNeeded()) {
            applyNoInstantLockPolicyIfNeeded()
            isBlackModeOn = true
            startBacklightEnforceLoop()
            return
        }
        floatingView?.visibility = View.VISIBLE
    }

    private fun exitBlackScreen() {
        isBlackModeOn = false
        stopBacklightEnforceLoop()
        clearScreenOffWakePending()
        restoreNoInstantLockPolicyIfNeeded()
        restoreBacklightIfNeeded()
        floatingView?.visibility = View.VISIBLE
    }

    private fun removeFloatingBall() {
        val view = floatingView ?: return
        runCatching {
            windowManager.removeView(view)
        }
        floatingView = null
        floatingParams = null
    }

    private fun stopServiceSafely() {
        longPressRunnable?.let(mainHandler::removeCallbacks)
        isBlackModeOn = false
        stopBacklightEnforceLoop()
        clearScreenOffWakePending()
        stopLanControlServer()
        restoreNoInstantLockPolicyIfNeeded()
        restoreBacklightIfNeeded()
        removeFloatingBall()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateFloatingBallSize(sizeDp: Int) {
        val finalSizeDp = sizeDp.coerceIn(AppSettings.MIN_FLOAT_SIZE_DP, AppSettings.MAX_FLOAT_SIZE_DP)
        ballSizeDp = finalSizeDp
        saveFloatingSizeDp(finalSizeDp)

        val params = floatingParams ?: return
        params.width = finalSizeDp.dp
        params.height = finalSizeDp.dp
        floatingView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun getSavedBallSizeDp(): Int {
        val value = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE).getInt(
            AppSettings.KEY_FLOAT_SIZE_DP,
            AppSettings.DEFAULT_FLOAT_SIZE_DP
        )
        return value.coerceIn(AppSettings.MIN_FLOAT_SIZE_DP, AppSettings.MAX_FLOAT_SIZE_DP)
    }

    private fun saveFloatingSizeDp(value: Int) {
        getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(AppSettings.KEY_FLOAT_SIZE_DP, value)
            .apply()
    }

    private fun applyBacklightOffIfNeeded(): Boolean {
        if (backlightModeApplied) {
            return true
        }

        brightnessModeBackup = RootShell.runForOutput("settings get system screen_brightness_mode")
        brightnessValueBackup = RootShell.runForOutput("settings get system screen_brightness")
        backlightPathValueBackup = readBacklightValues()
        blPowerPathValueBackup = readPathValues("/sys/class/backlight/*/bl_power")
        fbBlankPathValueBackup = readPathValues("/sys/class/graphics/fb*/blank")

        val modeOk = RootShell.run("settings put system screen_brightness_mode 0")
        val brightnessOk = RootShell.run("settings put system screen_brightness 0")
        enforceBacklightOffNow()

        backlightModeApplied = modeOk ||
            brightnessOk ||
            backlightPathValueBackup.isNotEmpty() ||
            blPowerPathValueBackup.isNotEmpty() ||
            fbBlankPathValueBackup.isNotEmpty()
        return backlightModeApplied
    }

    private fun restoreBacklightIfNeeded() {
        if (!backlightModeApplied) {
            return
        }

        restoreSystemSetting("screen_brightness_mode", brightnessModeBackup)
        restoreSystemSetting("screen_brightness", brightnessValueBackup)
        restoreBacklightValues(backlightPathValueBackup)
        restorePathValues(blPowerPathValueBackup)
        restorePathValues(fbBlankPathValueBackup)
        if (fbBlankPathValueBackup.isEmpty()) {
            RootShell.run("for f in /sys/class/graphics/fb*/blank; do [ -f \"\$f\" ] && echo 0 > \"\$f\"; done")
        }
        if (blPowerPathValueBackup.isEmpty()) {
            RootShell.run("for f in /sys/class/backlight/*/bl_power; do [ -f \"\$f\" ] && echo 0 > \"\$f\"; done")
        }

        val backupBrightness = brightnessValueBackup?.trim()?.toIntOrNull()
        if (backupBrightness != null) {
            val normalized = (backupBrightness / 255f).coerceIn(0f, 1f)
            RootShell.run("cmd display set-brightness $normalized")
        }

        brightnessModeBackup = null
        brightnessValueBackup = null
        backlightPathValueBackup = linkedMapOf()
        blPowerPathValueBackup = linkedMapOf()
        fbBlankPathValueBackup = linkedMapOf()
        backlightModeApplied = false
    }

    private fun readBacklightValues(): MutableMap<String, String> {
        val output = RootShell.runForOutput("for f in /sys/class/backlight/*/brightness; do [ -f \"\$f\" ] && echo \"\$f=\$(cat \$f)\"; done")
        return parsePathValueOutput(output)
    }

    private fun readPathValues(globPath: String): MutableMap<String, String> {
        val output = RootShell.runForOutput("for f in $globPath; do [ -f \"\$f\" ] && echo \"\$f=\$(cat \$f)\"; done")
        return parsePathValueOutput(output)
    }

    private fun parsePathValueOutput(output: String): MutableMap<String, String> {
        val map = linkedMapOf<String, String>()
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .forEach { line ->
                val index = line.indexOf('=')
                if (index > 0 && index < line.length - 1) {
                    val path = line.substring(0, index).trim()
                    val value = line.substring(index + 1).trim()
                    if (path.isNotBlank() && value.isNotBlank()) {
                        map[path] = value
                    }
                }
            }
        return map
    }

    private fun restoreBacklightValues(values: Map<String, String>) {
        values.forEach { (path, value) ->
            RootShell.run("echo ${quoteForShell(value)} > ${quoteForShell(path)}")
        }
    }

    private fun restorePathValues(values: Map<String, String>) {
        values.forEach { (path, value) ->
            RootShell.run("echo ${quoteForShell(value)} > ${quoteForShell(path)}")
        }
    }

    private fun enforceBacklightOffNow() {
        RootShell.run("settings put system screen_brightness_mode 0")
        RootShell.run("settings put system screen_brightness 0")
        RootShell.run("cmd display set-brightness 0")
        RootShell.run("for f in /sys/class/backlight/*/brightness; do [ -f \"\$f\" ] && echo 0 > \"\$f\"; done")
        RootShell.run("for f in /sys/class/backlight/*/bl_power; do [ -f \"\$f\" ] && echo 4 > \"\$f\"; done")
        RootShell.run("for f in /sys/class/graphics/fb*/blank; do [ -f \"\$f\" ] && echo 4 > \"\$f\"; done")
        RootShell.run("for f in /sys/class/leds/lcd-backlight/brightness /sys/class/leds/screen-backlight/brightness; do [ -f \"\$f\" ] && echo 0 > \"\$f\"; done")
    }

    private fun startBacklightEnforceLoop() {
        if (backlightEnforceRunnable != null) {
            return
        }
        val runnable = object : Runnable {
            override fun run() {
                if (!isBlackModeOn) {
                    backlightEnforceRunnable = null
                    return
                }
                enforceBacklightOffNow()
                mainHandler.postDelayed(this, 80)
            }
        }
        backlightEnforceRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopBacklightEnforceLoop() {
        val runnable = backlightEnforceRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        backlightEnforceRunnable = null
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) {
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isBlackModeOn) {
                    return
                }
                if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
                    enforceBacklightOffNow()
                    mainHandler.postDelayed({ if (isBlackModeOn) enforceBacklightOffNow() }, 25)
                    mainHandler.postDelayed({ if (isBlackModeOn) enforceBacklightOffNow() }, 120)
                }
                if (action == Intent.ACTION_SCREEN_OFF) {
                    triggerWakeByPowerKey()
                } else if (action == Intent.ACTION_SCREEN_ON) {
                    clearScreenOffWakePending()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
        screenStateReceiver = receiver
    }

    private fun unregisterScreenStateReceiver() {
        val receiver = screenStateReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        screenStateReceiver = null
    }

    private fun triggerWakeByPowerKey() {
        val now = System.currentTimeMillis()
        if (screenOffWakePending) {
            return
        }
        if (now - lastScreenOffWakeAtMs < SCREEN_OFF_WAKE_DEBOUNCE_MS) {
            return
        }
        screenOffWakePending = true
        lastScreenOffWakeAtMs = now
        clearWakePendingRunnable?.let(mainHandler::removeCallbacks)
        clearWakePendingRunnable = Runnable {
            screenOffWakePending = false
            clearWakePendingRunnable = null
        }
        mainHandler.postDelayed(clearWakePendingRunnable!!, SCREEN_OFF_WAKE_PENDING_TIMEOUT_MS)

        mainHandler.postDelayed({
            if (!isBlackModeOn) {
                clearScreenOffWakePending()
                return@postDelayed
            }
            RootShell.run("cmd power wakeup")
            RootShell.run("input keyevent KEYCODE_WAKEUP")
            mainHandler.postDelayed({ clearScreenOffWakePending() }, 240L)
        }, 80L)
    }

    private fun clearScreenOffWakePending() {
        clearWakePendingRunnable?.let(mainHandler::removeCallbacks)
        clearWakePendingRunnable = null
        screenOffWakePending = false
    }

    private fun applyNoInstantLockPolicyIfNeeded() {
        if (noInstantLockPolicyApplied) {
            return
        }

        backupSecureSetting(SECURE_KEY_POWER_BUTTON_INSTANT_LOCK)
        backupSecureSetting(SECURE_KEY_LOCK_AFTER_TIMEOUT)

        RootShell.run("settings put secure $SECURE_KEY_POWER_BUTTON_INSTANT_LOCK 0")
        RootShell.run("settings put secure $SECURE_KEY_LOCK_AFTER_TIMEOUT 2147483647")
        noInstantLockPolicyApplied = true
    }

    private fun restoreNoInstantLockPolicyIfNeeded() {
        if (!noInstantLockPolicyApplied) {
            return
        }

        restoreSecureSetting(SECURE_KEY_POWER_BUTTON_INSTANT_LOCK)
        restoreSecureSetting(SECURE_KEY_LOCK_AFTER_TIMEOUT)
        secureSettingsBackup.clear()
        noInstantLockPolicyApplied = false
    }

    private fun backupSecureSetting(key: String) {
        secureSettingsBackup[key] = RootShell.runForOutput("settings get secure $key")
    }

    private fun restoreSecureSetting(key: String) {
        val backup = secureSettingsBackup[key]?.trim().orEmpty()
        if (backup.isBlank() || backup.equals("null", ignoreCase = true)) {
            RootShell.run("settings delete secure $key")
        } else {
            RootShell.run("settings put secure $key ${quoteForShell(backup)}")
        }
    }

    private fun syncLanControlServerState() {
        if (isLanControlEnabled()) {
            startLanControlServerIfNeeded()
            return
        }
        stopLanControlServer()
    }

    private fun isLanControlEnabled(): Boolean {
        return getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE).getBoolean(
            AppSettings.KEY_LAN_CONTROL_ENABLED,
            false
        )
    }

    private fun startLanControlServerIfNeeded() {
        if (lanServerRunning) {
            return
        }

        lanServerRunning = true
        val thread = Thread {
            runCatching {
                ServerSocket(AppSettings.DEFAULT_LAN_PORT).use { server ->
                    lanServerSocket = server
                    while (lanServerRunning) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: continue
                        handleLanClient(socket)
                    }
                }
            }
            lanServerSocket = null
            lanServerRunning = false
        }
        lanServerThread = thread
        thread.start()
    }

    private fun stopLanControlServer() {
        lanServerRunning = false
        runCatching { lanServerSocket?.close() }
        lanServerSocket = null
        lanServerThread = null
    }

    private fun handleLanClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    break
                }
            }

            when (path) {
                "/black" -> {
                    mainHandler.post { enterBlackScreen() }
                    writeRedirectResponse(writer)
                }
                "/exit" -> {
                    mainHandler.post { exitBlackScreen() }
                    writeRedirectResponse(writer)
                }
                else -> {
                    writeIndexResponse(writer)
                }
            }
            writer.flush()
        }
    }

    private fun writeRedirectResponse(writer: BufferedWriter) {
        writer.write("HTTP/1.1 302 Found\r\n")
        writer.write("Location: /\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
    }

    private fun writeIndexResponse(writer: BufferedWriter) {
        val modeText = if (isBlackModeOn) "当前状态：黑屏中" else "当前状态：正常显示"
        val html = """
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>黑屏控制</title>
              <style>
                body { font-family: sans-serif; margin: 24px; background: #f6f7f9; color: #222; }
                .box { max-width: 420px; margin: 0 auto; background: white; padding: 20px; border-radius: 14px; box-shadow: 0 6px 20px rgba(0,0,0,.08); }
                h2 { margin-top: 0; }
                .row { display: flex; gap: 10px; margin-top: 16px; }
                a { display:inline-block; flex:1; text-align:center; text-decoration:none; padding: 12px 8px; border-radius: 10px; font-weight: 600; }
                .black { background:#111; color:white; }
                .exit { background:#1f6feb; color:white; }
                p { color:#444; }
              </style>
            </head>
            <body>
              <div class="box">
                <h2>黑屏挂机助手</h2>
                <p>$modeText</p>
                <div class="row">
                  <a class="black" href="/black">进入黑屏</a>
                  <a class="exit" href="/exit">退出黑屏</a>
                </div>
                <p>设备地址：${getLocalLanAddress()}</p>
              </div>
            </body>
            </html>
        """.trimIndent()

        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(html)
    }

    private fun getLocalLanAddress(): String {
        val ip = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?: "0.0.0.0"
        }.getOrDefault("0.0.0.0")
        return "http://$ip:${AppSettings.DEFAULT_LAN_PORT}/"
    }

    private fun restoreSystemSetting(key: String, rawBackup: String?) {
        val backup = rawBackup?.trim().orEmpty()
        if (backup.isBlank() || backup.equals("null", ignoreCase = true)) {
            RootShell.run("settings delete system $key")
            return
        }
        RootShell.run("settings put system $key ${quoteForShell(backup)}")
    }

    private fun quoteForShell(value: String): String {
        return "\"" + value.replace("\"", "\\\"") + "\""
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        val exitBlackIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_EXIT_BLACK_SCREEN
        }
        val exitBlackPendingIntent = PendingIntent.getService(
            this,
            2,
            exitBlackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, getString(R.string.notification_action_exit_black), exitBlackPendingIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_START = "com.example.lockscreen.ACTION_START"
        const val ACTION_STOP = "com.example.lockscreen.ACTION_STOP"
        const val ACTION_UPDATE_BALL_SIZE = "com.example.lockscreen.ACTION_UPDATE_BALL_SIZE"
        const val ACTION_EXIT_BLACK_SCREEN = "com.example.lockscreen.ACTION_EXIT_BLACK_SCREEN"
        const val ACTION_ENTER_BLACK_SCREEN = "com.example.lockscreen.ACTION_ENTER_BLACK_SCREEN"
        const val ACTION_SYNC_LAN_CONTROL = "com.example.lockscreen.ACTION_SYNC_LAN_CONTROL"
        const val EXTRA_FLOAT_SIZE_DP = "extra_float_size_dp"

        @Volatile
        var isRunning: Boolean = false
        @Volatile
        var isBlackModeOn: Boolean = false

        private const val CHANNEL_ID = "floating_service_channel"
        private const val NOTIFICATION_ID = 100
        private const val SCREEN_OFF_WAKE_DEBOUNCE_MS = 500L
        private const val SCREEN_OFF_WAKE_PENDING_TIMEOUT_MS = 1500L

        private const val SECURE_KEY_POWER_BUTTON_INSTANT_LOCK = "lockscreen.power_button_instantly_locks"
        private const val SECURE_KEY_LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout"
    }
}
