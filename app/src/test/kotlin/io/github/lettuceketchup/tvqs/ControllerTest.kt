package io.github.lettuceketchup.tvqs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTest {
    private val c = Controller(defaultTiles(), DEFAULT_PRESETS).apply {
        refresh(mapOf(TileId.BACKLIGHT to 37, TileId.WARMTH to 2, TileId.PICTURE to 0))
    }

    private fun Controller.press(k: Key) { onKey(k, true, 0); onKey(k, false, 100) }
    private fun Controller.hold(k: Key, ms: Long = 3000) { onKey(k, true, 0); onKey(k, false, ms) }
    private fun Controller.focusOn(id: TileId) { while (TileId.entries[state.focus] != id) press(Key.RIGHT) }

    @Test fun menuShortOpensBar() { c.press(Key.MENU); assertTrue(c.state.barOpen) }
    @Test fun menuHoldDoesNothing() { c.hold(Key.MENU); assertFalse(c.state.barOpen); assertTrue(c.effects.isEmpty()) }
    @Test fun menuHoldWhileOpenKeepsBar() { c.press(Key.MENU); c.hold(Key.MENU); assertTrue(c.state.barOpen) }
    @Test fun otherKeysPassWhenClosed() = assertFalse(c.onKey(Key.UP, true, 0))
    @Test fun leftFromFirstGoesToLast() { c.press(Key.MENU); c.press(Key.LEFT); assertEquals(TileId.SCREEN_OFF, TileId.entries[c.state.focus]) }
    @Test fun upOnBacklightWritesNextTick() { c.press(Key.MENU); c.press(Key.UP); assertTrue(Effect.Write("picture_backlight", 40) in c.effects) }
    @Test fun presetTileFollowsValues() { c.press(Key.MENU); assertEquals(-1, c.valueOf(TileId.PRESET)); c.press(Key.GREEN); assertEquals(1, c.valueOf(TileId.PRESET)) }

    // TV, 2026-11-03: a Green hold saved Day's values into Evening, and the tile then stuck on Day.
    @Test fun presetTileStepsPastDuplicatePresets() {
        c.press(Key.YELLOW); c.hold(Key.GREEN)
        c.press(Key.MENU); c.focusOn(TileId.PRESET)
        assertEquals(1, c.valueOf(TileId.PRESET))
        c.press(Key.UP)
        assertEquals(0, c.valueOf(TileId.PRESET))
        c.press(Key.DOWN)
        assertEquals(1, c.valueOf(TileId.PRESET))
    }

    @Test fun okOnPictureOpensPaneButNotOnWarmth() {
        c.press(Key.MENU); c.focusOn(TileId.PICTURE); c.press(Key.OK)
        assertEquals(0, c.state.paneIndex)
        c.press(Key.BACK); c.focusOn(TileId.WARMTH); c.press(Key.OK)
        assertNull(c.state.paneIndex)
    }

    @Test fun panePickWritesAndCloses() {
        c.press(Key.MENU); c.focusOn(TileId.PICTURE); c.press(Key.OK); c.press(Key.DOWN); c.press(Key.OK)
        assertTrue(Effect.Write(KEY_PICTURE, PICTURE_OPTIONS[1].value) in c.effects)
        assertNull(c.state.paneIndex)
    }

    @Test fun barReopensOnFirstTile() { c.press(Key.MENU); c.press(Key.RIGHT); c.press(Key.MENU); c.press(Key.MENU); assertEquals(0, c.state.focus) }

    @Test fun rightInPaneClosesItAndMovesOn() {
        c.press(Key.MENU); c.focusOn(TileId.PICTURE); c.press(Key.OK); c.press(Key.RIGHT)
        assertNull(c.state.paneIndex)
        assertEquals(TileId.SOUND, TileId.entries[c.state.focus])
    }

    @Test fun backClosesPaneThenBar() {
        c.press(Key.MENU); c.focusOn(TileId.PICTURE); c.press(Key.OK)
        c.press(Key.BACK); assertTrue(c.state.barOpen); assertNull(c.state.paneIndex)
        c.press(Key.BACK); assertFalse(c.state.barOpen)
    }

    @Test fun redAppliesNightWhileBarOpen() { c.press(Key.MENU); c.press(Key.RED); assertEquals(20, c.valueOf(TileId.BACKLIGHT)); assertTrue(c.state.barOpen); assertTrue(c.effects.any { it is Effect.Pill && it.title == "Night" }) }
    @Test fun redHoldSavesIntoNightWithoutApplying() { c.hold(Key.RED, 1500); assertTrue(c.effects.any { it is Effect.SavePresets && it.presets[2].backlight == 37 }); assertFalse(c.effects.any { it is Effect.Write }) }
    @Test fun greenHeld3sSwallowsFollowUp() { c.hold(Key.GREEN); assertTrue(c.onKey(Key.FOLLOW_UP, true, 0)); assertTrue(c.onKey(Key.FOLLOW_UP, false, 1)) }
    @Test fun followUpAlonePasses() = assertFalse(c.onKey(Key.FOLLOW_UP, true, 0))
    @Test fun blueHeld3sStaysCovered() { c.hold(Key.BLUE); c.press(Key.FOLLOW_UP); assertTrue(c.state.covered); assertFalse(Effect.Write("power_picture_off", 1) in c.effects) }

    @Test fun screenOffTileCovers() {
        c.press(Key.MENU); c.press(Key.LEFT); c.press(Key.OK)
        assertTrue(c.state.covered)
        assertTrue(Effect.Write("power_picture_off", 0) in c.effects)
        assertFalse(c.state.barOpen)
        assertTrue(c.onKey(Key.OK, true, 0))
    }

    @Test fun screenWentOffClearsCover() { c.press(Key.BLUE); c.screenWentOff(); assertFalse(c.state.covered); assertFalse(c.onKey(Key.OK, true, 0)) }
    @Test fun blueCoversAndNextKeyIsSwallowed() { c.press(Key.BLUE); assertTrue(c.state.covered); assertTrue(Effect.Write("power_picture_off", 0) in c.effects); assertTrue(c.onKey(Key.OK, true, 0)); assertTrue(c.onKey(Key.OK, false, 100)); assertTrue(Effect.Write("power_picture_off", 1) in c.effects); assertFalse(c.state.covered) }
    @Test fun warningOkExtendsByStart() { c.sleepStartedMin = 10; c.showSleepWarning(); c.press(Key.OK); assertTrue(Effect.ExtendSleep(10) in c.effects); assertFalse(c.state.warning) }
    @Test fun warningBackOnlyHides() { c.showSleepWarning(); c.press(Key.BACK); assertTrue(Effect.HidePill in c.effects); assertFalse(c.effects.any { it is Effect.ExtendSleep }) }
    @Test fun warningBeatsBar() { c.press(Key.MENU); c.showSleepWarning(); c.press(Key.BACK); assertTrue(c.state.barOpen) }
    @Test fun sleepUpFromOff() { c.press(Key.MENU); c.focusOn(TileId.SLEEP); c.sleepLeftMin = 0; c.press(Key.UP); assertTrue(Effect.SetSleep(10) in c.effects) }
    @Test fun sleepDownBelowSmallestCancels() { c.press(Key.MENU); c.focusOn(TileId.SLEEP); c.sleepLeftMin = 8; c.press(Key.DOWN); assertTrue(Effect.SetSleep(0) in c.effects) }
}
