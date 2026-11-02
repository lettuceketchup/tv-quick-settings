package io.github.lettuceketchup.tvqs

// Bar order; there's no edit mode.
enum class TileId { BACKLIGHT, WARMTH, PRESET, PICTURE, SOUND, OUTPUT, SLEEP, SCREEN_OFF }

data class Option(val label: String, val value: Int)

sealed interface Tile {
    val id: TileId
    val label: String
}

// Backlight only: 0–100 is already picture_backlight's raw range.
data class LevelTile(override val id: TileId, override val label: String, val key: String) : Tile

// key == null is the Preset tile, which writes through presets.
data class ModeTile(
    override val id: TileId,
    override val label: String,
    val key: String?,
    val options: List<Option>,
    val hasPane: Boolean,
) : Tile

// Screen off only.
data class ActionTile(override val id: TileId, override val label: String) : Tile

object SleepTile : Tile {
    override val id = TileId.SLEEP
    override val label = "Sleep"
}

data class Preset(val name: String, val backlight: Int, val warmth: Int)

// Haier setting keys and values, from docs/haier-tests.md. Mode keys take only these values:
// an unlisted picture_mode value corrupted the User mode on the TV.
const val KEY_BACKLIGHT = "picture_backlight"
const val KEY_WARMTH = "picture_color_temperature"
const val KEY_PICTURE = "picture_mode"
const val KEY_SOUND = "sound_style"
const val KEY_OUTPUT = "hdmi_system_audio_control_enabled"
const val KEY_SCREEN_OFF = "power_picture_off" // 0 = picture off, 1 = on
const val BACKLIGHT_STEP = 10

const val WARMTH_STANDARD = 2
const val WARMTH_WARM = 3

val WARMTH_OPTIONS = listOf(Option("User", 0), Option("Cool", 1), Option("Standard", WARMTH_STANDARD), Option("Warm", WARMTH_WARM))
val PICTURE_OPTIONS = listOf(
    Option("User", 0), Option("Standard", 7), Option("Vivid", 3), Option("Sport", 2),
    Option("Movie", 9), Option("Game", 10), Option("Energy Saving", 11),
)
val SOUND_OPTIONS = listOf(
    Option("User", 0), Option("Standard", 1), Option("Night", 2), Option("Sports", 3),
    Option("Movie", 4), Option("Music", 5), Option("News", 6),
)
val OUTPUT_OPTIONS = listOf(Option("TV speakers", 0), Option("External audio", 1))

val DEFAULT_PRESETS = listOf(
    Preset("Day", 100, WARMTH_STANDARD),
    Preset("Evening", 60, WARMTH_WARM),
    Preset("Night", 20, WARMTH_WARM),
)

fun defaultTiles(): Map<TileId, Tile> = listOf(
    LevelTile(TileId.BACKLIGHT, "Backlight", KEY_BACKLIGHT),
    ModeTile(TileId.WARMTH, "Warmth", KEY_WARMTH, WARMTH_OPTIONS, hasPane = false),
    ModeTile(TileId.PRESET, "Preset", null, DEFAULT_PRESETS.mapIndexed { i, p -> Option(p.name, i) }, hasPane = false),
    ModeTile(TileId.PICTURE, "Picture", KEY_PICTURE, PICTURE_OPTIONS, hasPane = true),
    ModeTile(TileId.SOUND, "Sound", KEY_SOUND, SOUND_OPTIONS, hasPane = true),
    ModeTile(TileId.OUTPUT, "Output", KEY_OUTPUT, OUTPUT_OPTIONS, hasPane = false),
    SleepTile,
    ActionTile(TileId.SCREEN_OFF, "Screen off"),
).associateBy { it.id }

// Next multiple of step in that direction; off-grid values (set from the TV's menu) snap to the neighbouring tick.
fun stepLevel(percent: Int, step: Int, up: Boolean): Int {
    val next = if (up) Math.floorDiv(percent, step) * step + step else Math.floorDiv(percent - 1, step) * step
    return next.coerceIn(0, 100)
}

// Up = the option shown above (index - 1). No wrap: null at an end. Unknown (-1): Up to last, Down to first.
fun stepMode(index: Int, size: Int, up: Boolean): Int? = when {
    index == -1 -> if (up) size - 1 else 0
    up -> (index - 1).takeIf { it >= 0 }
    else -> (index + 1).takeIf { it < size }
}

fun wrapFocus(index: Int, count: Int, right: Boolean): Int = Math.floorMod(index + if (right) 1 else -1, count)

fun modeLabel(tile: ModeTile, value: Int): String =
    tile.options.firstOrNull { it.value == value }?.label
        ?: if (tile.key == null) "Custom" else "Mode $value"

fun presetIndex(presets: List<Preset>, backlight: Int, warmth: Int): Int =
    presets.indexOfFirst { it.backlight == backlight && it.warmth == warmth }

// Stored form "Day:100:2;Evening:60:3;Night:20:3". Anything else falls back to the defaults.
fun formatPresets(presets: List<Preset>): String = presets.joinToString(";") { "${it.name}:${it.backlight}:${it.warmth}" }

fun parsePresets(s: String?): List<Preset> = runCatching {
    s!!.split(";").map { val (n, b, w) = it.split(":"); Preset(n, b.toInt(), w.toInt()) }
}.getOrNull()?.takeIf { it.size == DEFAULT_PRESETS.size } ?: DEFAULT_PRESETS
