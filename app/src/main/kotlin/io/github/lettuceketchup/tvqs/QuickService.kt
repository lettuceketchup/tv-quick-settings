package io.github.lettuceketchup.tvqs

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class QuickService : AccessibilityService() {

    // Debug-only adb hooks: `adb shell am broadcast -a <action>`.
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DEBUG_STANDBY -> Log.d(TAG, "standby: " + performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
            }
        }
    }

    override fun onServiceConnected() {
        if (BuildConfig.DEBUG) {
            registerReceiver(debugReceiver, IntentFilter(DEBUG_STANDBY))
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(
            TAG,
            "key=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) scan=${event.scanCode} " +
                "action=${event.action} repeat=${event.repeatCount} down=${event.downTime} time=${event.eventTime}",
        )
        return false
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    private companion object {
        const val TAG = "QuickService"
        const val DEBUG_STANDBY = "io.github.lettuceketchup.tvqs.DEBUG_STANDBY"
    }
}
