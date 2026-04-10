package com.example.lockscreen

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class VolumeKeyAccessibilityService : AccessibilityService() {

    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var lastVolumeUpDownTime = 0L
    private var lastVolumeDownDownTime = 0L
    private var lastTriggerTime = 0L
    private var guardUntilMs = 0L
    private var guardReleaseMode = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!FloatingService.isBlackModeOn) {
            return
        }

        val pkg = event?.packageName?.toString() ?: return
        if (pkg == SYSTEM_UI_PACKAGE) {
            dismissSystemPanels()
        }
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isVolumeUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeUp && !isVolumeDown) {
            return false
        }

        val now = System.currentTimeMillis()

        if (shouldHoldVolumeEvent(now)) {
            updatePressedState(isUpKey = isVolumeUp, action = event.action, eventTime = now)
            tryReleaseGuard(now)
            return true
        }

        if (!FloatingService.isBlackModeOn) {
            return false
        }

        handleVolumeState(isUpKey = isVolumeUp, action = event.action, event = event, now = now)
        return true
    }

    private fun handleVolumeState(isUpKey: Boolean, action: Int, event: KeyEvent, now: Long) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) {
                return
            }
            updatePressedState(isUpKey = isUpKey, action = action, eventTime = now)
            val closeChord =
                lastVolumeUpDownTime > 0L &&
                    lastVolumeDownDownTime > 0L &&
                    abs(lastVolumeUpDownTime - lastVolumeDownDownTime) <= CHORD_WINDOW_MS
            val shouldTrigger = closeChord
            if (shouldTrigger && now - lastTriggerTime > TRIGGER_DEBOUNCE_MS) {
                lastTriggerTime = now
                startReleaseGuard(now)
                requestExitBlackScreen()
            }
            return
        }

        updatePressedState(isUpKey = isUpKey, action = action, eventTime = now)
    }

    private fun updatePressedState(isUpKey: Boolean, action: Int, eventTime: Long) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (isUpKey) {
                volumeUpPressed = true
                lastVolumeUpDownTime = eventTime
            } else {
                volumeDownPressed = true
                lastVolumeDownDownTime = eventTime
            }
            return
        }
        if (action == KeyEvent.ACTION_UP) {
            if (isUpKey) {
                volumeUpPressed = false
            } else {
                volumeDownPressed = false
            }
        }
    }

    private fun startReleaseGuard(now: Long) {
        volumeUpPressed = false
        volumeDownPressed = false
        lastVolumeUpDownTime = 0L
        lastVolumeDownDownTime = 0L
        guardUntilMs = now + RELEASE_GUARD_MS
        guardReleaseMode = true
    }

    private fun shouldHoldVolumeEvent(now: Long): Boolean {
        return now < guardUntilMs || guardReleaseMode
    }

    private fun tryReleaseGuard(now: Long) {
        if (guardReleaseMode && now >= guardUntilMs) {
            guardReleaseMode = false
            guardUntilMs = 0L
            volumeUpPressed = false
            volumeDownPressed = false
        }
    }

    private fun requestExitBlackScreen() {
        val intent = android.content.Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_EXIT_BLACK_SCREEN
        }
        startService(intent)
    }

    private fun dismissSystemPanels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CHORD_WINDOW_MS = 500L
        private const val TRIGGER_DEBOUNCE_MS = 450L
        private const val RELEASE_GUARD_MS = 700L
    }
}
