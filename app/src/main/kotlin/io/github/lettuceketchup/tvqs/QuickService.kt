package io.github.lettuceketchup.tvqs

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class QuickService : AccessibilityService() {

    private lateinit var controller: Controller
    private val tiles = defaultTiles()
    private lateinit var pill: PillView
    private lateinit var bar: BarView
    private var simView: SimView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { controller.timeoutClose(); render() }

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
        controller = Controller(tiles, parsePresets(prefs().getString("presets", null)))
        // The emulator has no Haier panel, so debug builds there fake one under the overlays.
        if (BuildConfig.DEBUG && Build.HARDWARE == "ranchu") {
            simView = SimView(this).also { overlay(it, Gravity.FILL) { width = MATCH; height = MATCH } }
        }
        pill = PillView(this)
        val margin = (32 * resources.displayMetrics.density).toInt()
        overlay(pill, Gravity.TOP or Gravity.END) { x = margin; y = margin }
        bar = BarView(this)
        overlay(bar, Gravity.BOTTOM) { width = MATCH }
        refresh()
        if (BuildConfig.DEBUG) {
            registerReceiver(debugReceiver, IntentFilter(DEBUG_STANDBY).apply { addAction(DEBUG_KEY) })
        }
    }

    // Adds view as a non-focusable, non-touchable overlay window and returns its params.
    fun overlay(view: View, gravity: Int, setup: WindowManager.LayoutParams.() -> Unit = {}) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        setup()
        getSystemService(WindowManager::class.java).addView(view, this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        return handleKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime - event.downTime)
    }

    fun handleKey(keyCode: Int, down: Boolean, heldMs: Long): Boolean {
        val key = keyOf(keyCode)
        if (!down && (key == Key.MENU || key in COLOURS)) refresh()
        val consumed = controller.onKey(key, down, heldMs)
        drain()
        render()
        handler.removeCallbacks(timeout)
        if (controller.state.barOpen) handler.postDelayed(timeout, BAR_TIMEOUT_MS)
        Log.d(TAG, "key=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) down=$down held=$heldMs consumed=$consumed")
        return consumed
    }

    private fun drain() {
        val effects = controller.effects.toList()
        controller.effects.clear()
        for (e in effects) when (e) {
            is Effect.Write -> write(e.key, e.raw)
            is Effect.Pill -> pill.show(e)
            Effect.HidePill -> pill.hide()
            is Effect.SavePresets -> prefs().edit().putString("presets", formatPresets(e.presets)).apply()
            is Effect.SetSleep, is Effect.ExtendSleep -> {} // TODO Task 8: sleep timer
        }
        if (effects.any { it is Effect.Write }) updateSim()
    }

    private fun write(key: String, raw: Int) {
        try {
            Settings.Global.putInt(contentResolver, key, raw)
        } catch (e: SecurityException) {
            pill.show(Effect.Pill("Permission missing", "Grant WRITE_SECURE_SETTINGS over adb", null, 4000))
            return
        }
        // The TV keeps backlight (maybe warmth) per picture mode and updates them itself.
        if (key == KEY_PICTURE) handler.postDelayed({ refresh() }, 300)
    }

    private fun read(key: String, default: Int) = Settings.Global.getInt(contentResolver, key, default)

    private fun refresh() {
        controller.refresh(mapOf(
            TileId.BACKLIGHT to read(KEY_BACKLIGHT, 100),
            TileId.WARMTH to read(KEY_WARMTH, WARMTH_STANDARD),
            TileId.PICTURE to read(KEY_PICTURE, 0),
            TileId.SOUND to read(KEY_SOUND, 1),
            TileId.OUTPUT to read(KEY_OUTPUT, 0),
        ))
        updateSim()
        render()
    }

    private fun render() = bar.render(controller.state, controller, tiles)

    private fun updateSim() {
        simView?.update(read(KEY_BACKLIGHT, 100), read(KEY_WARMTH, WARMTH_STANDARD), read(KEY_SCREEN_OFF, 1) == 0)
    }

    private fun prefs() = getSharedPreferences("tvqs", MODE_PRIVATE)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    private companion object {
        const val TAG = "QuickService"
        const val DEBUG_STANDBY = "io.github.lettuceketchup.tvqs.DEBUG_STANDBY"
        const val DEBUG_KEY = "io.github.lettuceketchup.tvqs.DEBUG_KEY"
        const val BAR_TIMEOUT_MS = 8000L
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
    }
}

// Emulator stand-in for the panel: grey picture, warmth tint, backlight as dimming. An approximation.
private class SimView(ctx: Context) : View(ctx) {
    private var backlight = 100
    private var warmth = WARMTH_STANDARD
    private var pictureOff = false

    fun update(backlight: Int, warmth: Int, pictureOff: Boolean) {
        this.backlight = backlight.coerceIn(0, 100)
        this.warmth = warmth
        this.pictureOff = pictureOff
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFC8C8C8.toInt())
        when (warmth) {
            WARMTH_WARM -> c.drawColor(Color.argb(46, 0xFF, 0x9A, 0x3C)) // 18%
            1 -> c.drawColor(Color.argb(31, 0x5A, 0xA0, 0xFF)) // Cool, 12%
        }
        c.drawColor(Color.argb((100 - backlight) * 0.8f / 100, 0f, 0f, 0f))
        if (pictureOff) c.drawColor(Color.BLACK)
    }
}
