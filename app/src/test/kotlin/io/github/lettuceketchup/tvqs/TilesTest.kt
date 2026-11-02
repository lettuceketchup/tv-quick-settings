package io.github.lettuceketchup.tvqs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TilesTest {
    @Test fun backlightSnapsUpFromOffGrid() = assertEquals(40, stepLevel(37, 10, up = true))
    @Test fun backlightSnapsDownFromOffGrid() = assertEquals(30, stepLevel(37, 10, up = false))
    @Test fun backlightClampsAtTop() = assertEquals(100, stepLevel(100, 10, up = true))
    @Test fun warmthLabels() = assertEquals("Warm", modeLabel(defaultTiles()[TileId.WARMTH] as ModeTile, 3))
    @Test fun modeUpAtFirstIsNull() = assertNull(stepMode(0, 2, up = true))
    @Test fun modeDownAtLastIsNull() = assertNull(stepMode(1, 2, up = false))
    @Test fun modeDownMovesToNext() = assertEquals(1, stepMode(0, 2, up = false))
    @Test fun unknownModeValue() { assertEquals(4, stepMode(-1, 5, true)); assertEquals(0, stepMode(-1, 5, false)) }
    @Test fun unknownModeLabel() = assertEquals("Mode 7", modeLabel(ModeTile(TileId.PICTURE, "Picture", "picture_mode", listOf(Option("Standard", 0)), true), 7))
    @Test fun focusWrapsBothWays() { assertEquals(7, wrapFocus(0, 8, right = false)); assertEquals(0, wrapFocus(7, 8, right = true)) }
    @Test fun pictureOptionsOnlyKnownValuesInMenuOrder() = assertEquals(listOf(0, 7, 3, 2, 9, 10, 11), (defaultTiles()[TileId.PICTURE] as ModeTile).options.map { it.value })
    @Test fun soundOptionsOnlyKnownValues() = assertEquals((0..6).toList(), (defaultTiles()[TileId.SOUND] as ModeTile).options.map { it.value })
    @Test fun presetMatchesBothValues() { assertEquals(1, presetIndex(DEFAULT_PRESETS, 60, 3)); assertEquals(-1, presetIndex(DEFAULT_PRESETS, 60, 2)) }
    @Test fun presetUnknownIsCustom() = assertEquals("Custom", modeLabel(defaultTiles()[TileId.PRESET] as ModeTile, -1))
    @Test fun presetsRoundTrip() { assertEquals("Day:100:2;Evening:60:3;Night:20:3", formatPresets(DEFAULT_PRESETS)); assertEquals(DEFAULT_PRESETS, parsePresets(formatPresets(DEFAULT_PRESETS))) }
    @Test fun badPresetsFallBack() = listOf(null, "", "Day:100", "Day:x:2;Evening:60:3;Night:20:3", "Day:100:2").forEach { assertEquals(DEFAULT_PRESETS, parsePresets(it)) }
}
