# Plan review, pass 1 (independent)

Date: 2026-11-02. Reviewer: Claude Opus 5.5. Target: the plan, Tasks 2–10. Written before reading the findings in `2026-11-02-ponytail-review.md`.

Read: `CLAUDE.md`, the spec, the plan, `docs/haier-tests.md`, `README.md`, and everything under `app/` (Task 1's probe).

## High: correctness gaps

**H1. The long-press follow-up swallow is missing from Task 5.** Global Constraints say that after a long colour press the service consumes the remote's follow-up key (Red → 130 `MEDIA_RECORD`, Green → 127 `MEDIA_PAUSE`, Yellow → 86 `MEDIA_STOP`, Blue → 217 `RO`). Task 5 has no `Key` value for these codes (they map to `OTHER` and pass through), no routing rule and no test. The "within 500 ms" window also needs an absolute time, which `onKey(key, down, heldMs)` doesn't get. The user gets no feedback while holding a colour (the save happens on release), so they'll often hold past 3 s. Green would then pause YouTube on every save.
*Fix:* add `Key.FOLLOW_UP` for 130/127/86/217. If the event right before it was a colour UP with `heldMs ≥ 1000`, consume the follow-up's DOWN and UP. Otherwise it passes. Checking the previous event is enough, because the follow-up is a 1 ms press sent right after the UP, so no clock is needed. Make this the first routing rule. Tests: `greenHeld3sSwallowsMediaPause` and `mediaPauseAloneStillPasses`.

**H2. Holding Blue for 3 s turns the screen off and then straight back on.** Blue long is "same as short", so it sets `covered`. Then the follow-up 217 DOWN arrives, rule 1 (covered) treats it as "the next key", and writes `power_picture_off 1`. H1's rule, placed before rule 1, fixes it. Test: `blueHeld3sStaysCovered`.

**H3. Switching picture mode makes the bar's values stale.** Task 0 found that backlight is stored per picture mode, and the key reports the current mode's value. The controller only reads values when the bar opens, so after Picture → Movie the Backlight tile still shows the old number, the Preset tile matches against it, and the next Up writes the old value + 10 into the new mode.
*Fix:* after a `picture_mode` write, the service re-reads the values and re-renders. Post this about 300 ms later, because the firmware updates the key asynchronously; check the delay on the TV. Add a check to Task 7: switch Picture in the bar, and the Backlight tile shows that mode's backlight.

**H4. The sleep timer outlives a manual power-off.** The countdown is a `Handler` plus `elapsedRealtime`. If the user sets 60 min, turns the TV off with the remote after 20 min, and turns it on later, the tick finds the time has passed and puts the TV straight back into standby.
*Fix:* cancel the timer on `ACTION_SCREEN_OFF` (a three-line receiver in the service). The same receiver clears `covered`, so the first press after waking isn't swallowed.

## Medium

**M1. A dismissed last-minute warning comes back.** Task 8 shows the warning "if `lastMinute(now)` and the warning isn't up yet". After Back, the controller's `warning` is false, so the next 15 s tick shows it again. The service needs its own `warned` flag per countdown, reset by `SetSleep` and `ExtendSleep`.

**M2. HDMI was never tested.** Task 1's key log ran only in YouTube. Opening the bar on HDMI is an explicit goal, and on Live TV/HDMI the firmware may take MENU or the colour keys (teletext) before the accessibility filter, or draw its video plane over the overlay. Add to Task 6 Step 4: on HDMI, press Red and MENU. Expected: the pill shows, and Haier's own HDMI menu doesn't open.

**M3. In Task 6 the bar can open invisibly and trap the remote.** Task 6 wires up the Controller, but the bar view and the 8 s timeout only come in Task 7. A short MENU during Task 6's checks opens a bar nobody can see, and it swallows the arrows, OK and Back until MENU is pressed again. Move the timeout `Handler` into Task 6.

**M4. The Screen off tile doesn't set `covered`.** Rule 5 says "OK on an action emits its effect and closes the bar". Only Blue sets `covered`, so after the tile turns the screen off, the next key isn't swallowed and goes through to the video (for example, OK pauses it). The tile should use the same path as Blue. Add a test.

## Low

- **L1.** Holding MENU while the bar is open: Settings opens under a bar that keeps taking the arrow keys. Closing the bar on a long MENU press is simpler for the user, but the spec says "does nothing", so that's the user's call. If it changes, the `menuHoldWhileOpenKeepsBar` test flips.
- **L2.** A colour press during the last-minute warning replaces the warning pill with a 2 s pill. When that fades, the warning is still active and invisible, and it takes OK and Back. Fix: while `warning` is on, the service skips other `Pill`s.
- **L3.** Spell out when values are refreshed: before every MENU and colour UP is passed to `onKey`, not just "before the bar opens" (the controller decides that).
- **L4.** Text fixes. Review Focus 2 uses `picture_mode=7` as its unknown value, but 7 is Standard; use 5. Global Constraints still mention "6 h and 2 h from the spec", which the spec no longer has. The Task 9 stub can go. `focusWrapsBothWays` uses 11 tiles (there are 9), which is harmless.

## Over-engineering

Keep:
- **The pure `Controller` with `Effect`s.** All the bugs above are key-routing bugs (holds, follow-ups, covered, warning), and this is the only way to unit-test them without Robolectric. It pays for itself.
- **`pictureOptionsOnlyKnownValuesInMenuOrder`.** This is a data-loss guard: an unknown `picture_mode` value corrupted the User mode on the TV.
- **The release build.** It's one `signingConfig` line, and it removes the debug broadcast (which any app on the TV can send to lock the screen) and the logging of every key.

Small cuts (my call, low payoff):
- **Tile types.** `ActionTile`, `SleepTile` and `EditTile` each have one instance and no data. `when (id)` would do. Only `ModeTile` (key and options) and the backlight key carry real data. Optional.
- **`LevelTile.segments`.** There's one level tile, always 10 segments. A constant would do.
- **`Store.kt`.** It's four SharedPreferences calls and could live in the service. Optional.
- **Task 9 stub.** Delete it.

Big cuts. These are the user's call: all were settled in the mocks, and I don't recommend any of them on my own.
- **Edit mode** is the biggest optional piece: all of Task 4, about a third of Task 5's rules and tests, and the most complex part of `BarView`. Reordering 9 tiles could be a constant in code.
- **The list pane** for Picture and Sound. Up/Down already reaches every option.
- **Long-press save of presets.** Without it, presets are constants in code, `SavePresets`, the `presets` key in `Store` and the hold-to-save tests go away, and H1 matters less (H2 still needs the fix).
- **Hand-drawn icons.** Unicode glyphs throughout would cost nothing.
