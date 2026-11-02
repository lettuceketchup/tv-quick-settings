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
                // Emulator demos: injected keys don't reach onKeyEvent there, so scripts press keys through here.
                DEBUG_KEY -> {
                    val code = KeyEvent.keyCodeFromString(intent.getStringExtra("key") ?: return)
                    handleKey(code, true, 0)
                    handleKey(code, false, intent.getLongExtra("hold", 100))
                }
            }
        }
    }

    override fun onServiceConnected() {
        if (BuildConfig.DEBUG) {
            registerReceiver(debugReceiver, IntentFilter(DEBUG_STANDBY).apply { addAction(DEBUG_KEY) })
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        return handleKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime - event.downTime)
    }

    // Task 6 routes keys through the Controller here.
    fun handleKey(keyCode: Int, down: Boolean, heldMs: Long): Boolean {
        val consumed = false
        Log.d(TAG, "key=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) down=$down held=$heldMs consumed=$consumed")
        return consumed
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
        const val DEBUG_KEY = "io.github.lettuceketchup.tvqs.DEBUG_KEY"
    }
}
