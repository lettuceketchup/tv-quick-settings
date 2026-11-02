package io.github.lettuceketchup.tvqs

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    private var sleepEndAt = 0L // elapsedRealtime; 0 = off
    private val sleepEvents = mutableListOf<Runnable>()

    // The TV changes values itself (each picture mode keeps its own backlight, and its menu can change any key),
    // and late: a fixed 300 ms re-read after a picture_mode write still saw the old mode's backlight.
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            Log.d(TAG, "changed ${uri?.lastPathSegment}")
            refresh()
        }
    }

    // Debug-only adb hooks: `adb shell am broadcast -a <action>`.
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DEBUG_STANDBY -> Log.d(TAG, "standby: " + performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
                // Emulator demos: injected keys don't reach onKeyEvent there, so scripts press keys through here.
                DEBUG_SLEEP -> schedule(intent.getIntExtra("ms", 65_000).toLong())
                DEBUG_KEY -> {
                    val code = KeyEvent.keyCodeFromString(intent.getStringExtra("key") ?: return)
                    handleKey(code, true, 0)
                    handleKey(code, false, intent.getLongExtra("hold", 100))
                }
            }
        }
    }

    // The power button's standby. Picture off may send it too while the TV stays interactive; that keeps the timer.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (getSystemService(PowerManager::class.java).isInteractive) return
            cancelSleep()
            // The TV wakes with the picture on but keeps power_picture_off at 0, and a later write of 0 over 0 does nothing.
            write(KEY_SCREEN_OFF, 1)
            controller.screenWentOff()
            render()
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
        // Full screen, so opening the pane never resizes the window: a resize made the bar jump on the TV.
        overlay(bar, Gravity.BOTTOM) { width = MATCH; height = MATCH }
        refresh()
        for (k in listOf(KEY_BACKLIGHT, KEY_WARMTH, KEY_PICTURE, KEY_SOUND, KEY_OUTPUT)) {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(k), false, observer)
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        if (BuildConfig.DEBUG) {
            registerReceiver(debugReceiver, IntentFilter(DEBUG_STANDBY).apply { addAction(DEBUG_KEY); addAction(DEBUG_SLEEP) })
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
        updateSleepLeft()
        val consumed = controller.onKey(key, down, heldMs)
        drain()
        render()
        handler.removeCallbacks(timeout)
        if (controller.state.barOpen) handler.postDelayed(timeout, BAR_TIMEOUT_MS)
        Log.d(TAG, "key=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) down=$down held=$heldMs consumed=$consumed ${controller.state}")
        return consumed
    }

    private fun drain() {
        val effects = controller.effects.toList()
        controller.effects.clear()
        for (e in effects) when (e) {
            is Effect.Write -> write(e.key, e.raw)
            is Effect.Pill -> if (!controller.state.warning) pill.show(e)
            Effect.HidePill -> pill.hide()
            is Effect.SavePresets -> prefs().edit().putString("presets", formatPresets(e.presets)).apply()
            is Effect.SetSleep -> { controller.sleepStartedMin = e.minutes; schedule(e.minutes * 60_000L) }
            is Effect.ExtendSleep -> schedule(sleepEndAt + e.minutes * 60_000L - SystemClock.elapsedRealtime())
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

    // Posts each sleep event once (reminders, the last-minute warning, standby); no tick loop. msLeft <= 0 cancels.
    // ponytail: in-memory timer, gone if the service restarts; persist sleepEndAt if restarts turn out to happen.
    private fun schedule(msLeft: Long) {
        cancelSleep()
        if (msLeft <= 0) return
        sleepEndAt = SystemClock.elapsedRealtime() + msLeft
        val started = controller.sleepStartedMin
        for (m in remindersAhead(started, msLeft)) {
            post(msLeft - m * 60_000L) { pill.show(Effect.Pill("Sleeping in $m min", "Sleep timer", null, 3000), R.drawable.ic_sleep) }
        }
        post(msLeft - 60_000) {
            controller.showSleepWarning()
            render()
            pill.show(Effect.Pill("Sleeping in 1 min", "The TV will turn off", null, 0), R.drawable.ic_sleep,
                listOf("OK" to "+${extensionMin(started)} min", "Back" to "Dismiss"))
        }
        post(msLeft) {
            controller.clearSleepWarning()
            pill.hide()
            cancelSleep()
            render()
            Log.d(TAG, "sleep: standby " + performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
        }
        render()
    }

    private fun post(delayMs: Long, block: () -> Unit) {
        val r = Runnable(block)
        sleepEvents += r
        handler.postDelayed(r, delayMs.coerceAtLeast(0))
    }

    private fun cancelSleep() {
        sleepEvents.forEach(handler::removeCallbacks)
        sleepEvents.clear()
        sleepEndAt = 0
        updateSleepLeft()
    }

    private fun updateSleepLeft() {
        controller.sleepLeftMin = if (sleepEndAt == 0L) 0 else minutesLeft(sleepEndAt - SystemClock.elapsedRealtime())
    }

    private fun render() {
        updateSleepLeft()
        bar.render(controller.state, controller, tiles)
    }

    private fun updateSim() {
        simView?.update(read(KEY_BACKLIGHT, 100), read(KEY_WARMTH, WARMTH_STANDARD), read(KEY_SCREEN_OFF, 1) == 0)
    }

    private fun prefs() = getSharedPreferences("tvqs", MODE_PRIVATE)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(screenOffReceiver)
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    private companion object {
        const val TAG = "QuickService"
        const val DEBUG_STANDBY = "io.github.lettuceketchup.tvqs.DEBUG_STANDBY"
        const val DEBUG_KEY = "io.github.lettuceketchup.tvqs.DEBUG_KEY"
        const val DEBUG_SLEEP = "io.github.lettuceketchup.tvqs.DEBUG_SLEEP"
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
