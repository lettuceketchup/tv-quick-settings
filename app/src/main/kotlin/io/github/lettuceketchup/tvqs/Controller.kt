package io.github.lettuceketchup.tvqs

import android.view.KeyEvent

enum class Key { MENU, UP, DOWN, LEFT, RIGHT, OK, BACK, RED, GREEN, YELLOW, BLUE, FOLLOW_UP, OTHER }

// Keycodes from Task 1's key log. FOLLOW_UP is the key the remote sends after a 3 s colour hold; MENU's (250) stays OTHER.
fun keyOf(keyCode: Int): Key = when (keyCode) {
    KeyEvent.KEYCODE_MENU -> Key.MENU
    KeyEvent.KEYCODE_DPAD_UP -> Key.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.OK
    KeyEvent.KEYCODE_BACK -> Key.BACK
    KeyEvent.KEYCODE_PROG_RED -> Key.RED
    KeyEvent.KEYCODE_PROG_GREEN -> Key.GREEN
    KeyEvent.KEYCODE_PROG_YELLOW -> Key.YELLOW
    KeyEvent.KEYCODE_PROG_BLUE -> Key.BLUE
    KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_RO -> Key.FOLLOW_UP
    else -> Key.OTHER
}

sealed interface Effect {
    data class Write(val key: String, val raw: Int) : Effect
    data class Pill(val title: String, val detail: String, val levelPercent: Int?, val millis: Long) : Effect // millis 0 = stays up
    data object HidePill : Effect
    data class SetSleep(val minutes: Int) : Effect // 0 = cancel
    data class ExtendSleep(val minutes: Int) : Effect
    data class SavePresets(val presets: List<Preset>) : Effect
}

data class UiState(val barOpen: Boolean, val focus: Int, val paneIndex: Int?, val covered: Boolean, val warning: Boolean)

const val LONG_PRESS_MS = 1000L
private val COLOURS = setOf(Key.RED, Key.GREEN, Key.YELLOW, Key.BLUE)
// Index into presets (Day, Evening, Night).
private val COLOUR_PRESET = mapOf(Key.RED to 2, Key.GREEN to 1, Key.YELLOW to 0)

// Pure key routing. The service calls onKey for every key and drains effects after each call.
class Controller(private val tiles: Map<TileId, Tile>, presets: List<Preset>) {
    private val presets = presets.toMutableList()
    private val values = mutableMapOf<TileId, Int>()
    private val swallowUp = mutableSetOf<Key>() // keys whose DOWN was consumed, so their UP is too
    private var afterLongColourUp = false

    val effects = mutableListOf<Effect>()
    var sleepLeftMin = 0
    var sleepStartedMin = 0

    private var barOpen = false
    private var focus = 0
    private var paneIndex: Int? = null
    private var covered = false
    private var warning = false
    val state get() = UiState(barOpen, focus, paneIndex, covered, warning)

    fun refresh(values: Map<TileId, Int>) { this.values.putAll(values) }

    fun valueOf(id: TileId): Int? = when (id) {
        TileId.PRESET -> values[TileId.BACKLIGHT]?.let { b -> values[TileId.WARMTH]?.let { w -> presetIndex(presets, b, w) } }
        TileId.SLEEP -> sleepLeftMin
        else -> values[id]
    }

    fun showSleepWarning() { warning = true }
    fun clearSleepWarning() { warning = false }
    fun timeoutClose() { barOpen = false; paneIndex = null }
    fun screenWentOff() { covered = false; warning = false; timeoutClose() }

    fun onKey(key: Key, down: Boolean, heldMs: Long): Boolean {
        // Rule 0: the remote's follow-up key right after a long colour hold.
        val followsLongColour = afterLongColourUp
        afterLongColourUp = !down && key in COLOURS && heldMs >= LONG_PRESS_MS
        if (key == Key.FOLLOW_UP && down && followsLongColour) return swallow(key)
        if (!down && swallowUp.remove(key)) return true
        val k = if (key == Key.FOLLOW_UP) Key.OTHER else key

        // Rule 1: any key uncovers the screen, and that press goes nowhere else.
        if (covered && down) {
            covered = false
            effects += Effect.Write(KEY_SCREEN_OFF, 1)
            return swallow(key)
        }
        // Rule 2: the last-minute pill answers OK and Back only.
        if (warning && down && (k == Key.OK || k == Key.BACK)) {
            if (k == Key.OK) effects += Effect.ExtendSleep(extensionMin(sleepStartedMin))
            effects += Effect.HidePill
            warning = false
            return swallow(key)
        }
        // Rule 3: MENU and colours act on release.
        if (k == Key.MENU || k in COLOURS) {
            if (!down) release(k, heldMs >= LONG_PRESS_MS)
            return true
        }
        // Rules 4–5: the bar takes the arrows, OK and Back while it's open.
        if (!barOpen || k == Key.OTHER || !down) return false
        if (paneIndex != null) paneKey(k) else barKey(k)
        return swallow(key)
    }

    private fun swallow(key: Key): Boolean { swallowUp += key; return true }

    private fun release(k: Key, long: Boolean) {
        when (k) {
            Key.MENU -> if (!long) { barOpen = !barOpen; paneIndex = null }
            Key.BLUE -> screenOff()
            else -> {
                val i = COLOUR_PRESET.getValue(k)
                if (long) savePreset(i) else applyPreset(i, pill = true)
            }
        }
    }

    private fun screenOff() {
        timeoutClose()
        covered = true
        effects += Effect.Write(KEY_SCREEN_OFF, 0)
    }

    private fun warmthLabel(v: Int) = modeLabel(tiles[TileId.WARMTH] as ModeTile, v)

    private fun savePreset(i: Int) {
        val b = values[TileId.BACKLIGHT] ?: return
        val w = values[TileId.WARMTH] ?: return
        presets[i] = presets[i].copy(backlight = b, warmth = w)
        effects += Effect.SavePresets(presets.toList())
        effects += Effect.Pill("Saved to ${presets[i].name}", "Backlight $b · ${warmthLabel(w)}", b, 2000)
    }

    private fun applyPreset(i: Int, pill: Boolean) {
        val p = presets[i]
        write(TileId.BACKLIGHT, KEY_BACKLIGHT, p.backlight)
        write(TileId.WARMTH, KEY_WARMTH, p.warmth)
        if (pill) effects += Effect.Pill(p.name, "Backlight ${p.backlight} · ${warmthLabel(p.warmth)}", p.backlight, 2000)
    }

    private fun write(id: TileId, key: String, raw: Int) {
        values[id] = raw
        effects += Effect.Write(key, raw)
    }

    private fun barKey(k: Key) {
        val tile = tiles.getValue(TileId.entries[focus])
        when (k) {
            Key.LEFT, Key.RIGHT -> focus = wrapFocus(focus, TileId.entries.size, right = k == Key.RIGHT)
            Key.UP, Key.DOWN -> step(tile, up = k == Key.UP)
            Key.OK -> when {
                tile is ModeTile && tile.hasPane -> paneIndex = optionIndex(tile).coerceAtLeast(0)
                tile is ActionTile -> screenOff()
            }
            Key.BACK -> barOpen = false
            else -> {}
        }
    }

    private fun paneKey(k: Key) {
        val tile = tiles.getValue(TileId.entries[focus]) as ModeTile
        val i = paneIndex!!
        when (k) {
            Key.UP -> paneIndex = (i - 1).coerceAtLeast(0)
            Key.DOWN -> paneIndex = (i + 1).coerceAtMost(tile.options.lastIndex)
            Key.OK -> { write(tile.id, tile.key!!, tile.options[i].value); paneIndex = null }
            Key.BACK -> paneIndex = null
            else -> {}
        }
    }

    private fun optionIndex(tile: ModeTile): Int = tile.options.indexOfFirst { it.value == values[tile.id] }

    private fun step(tile: Tile, up: Boolean) {
        when (tile) {
            is LevelTile -> {
                val cur = values[tile.id] ?: return
                val next = stepLevel(cur, BACKLIGHT_STEP, up)
                if (next != cur) write(tile.id, tile.key, next)
            }
            is ModeTile -> if (tile.key == null) {
                val next = stepMode(valueOf(TileId.PRESET) ?: return, presets.size, up) ?: return
                applyPreset(next, pill = false)
            } else {
                val next = stepMode(optionIndex(tile), tile.options.size, up) ?: return
                write(tile.id, tile.key, tile.options[next].value)
            }
            SleepTile -> (if (up) nextLonger(sleepLeftMin) else nextShorter(sleepLeftMin))?.let { effects += Effect.SetSleep(it) }
            is ActionTile -> {}
        }
    }
}
