# TV Quick Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Android TV app for the Haier. In any app, MENU opens a translucent bar of picture/sound tiles, and the colour buttons apply Day/Evening/Night presets or turn the screen off. The app works with USB debugging off.

**Architecture:** One accessibility service filters remote key events and draws two accessibility-overlay windows: the bar and the corner pill. All behaviour lives in a pure Kotlin `Controller` state machine. It takes keys and a snapshot of setting values, and returns `Effect`s. The service performs those effects: writing `Settings.Global`, showing the pill, starting activities, running the sleep timer. The pure logic is covered by JVM unit tests. Views and Android calls are checked on the TV with `adb exec-out screencap`.

**Tech Stack:** Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Gradle 8.7, JDK 17, compileSdk 34, minSdk/targetSdk 30 (the TV runs Android 11). Programmatic Android Views, with no layout XML beyond the service config. No runtime libraries. JUnit 4.13.2 for tests only.

**Spec:** `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md`. Read it first; this plan refers to its sections by name.

## Global Constraints

- Package `io.github.lettuceketchup.tvqs`, app label "TV Quick Settings".
- No runtime dependencies. The only test dependency is `junit:junit:4.13.2`.
- Work on the TV is reversible only. Every setting written during tests is restored and logged in `../tvs/haier/changes.md` (date, key, old → new → restored, result).
- **Never operate the TV while someone is watching.** Before every adb step that changes what's on screen, ask the user whether the TV is free. Read-only commands (`settings get`, `dumpsys`, `getevent`) are fine at any time, but `getevent` needs the user to press buttons.
- Haier adb: `adb connect 192.168.29.186:5555`. The IP isn't reserved; if that fails, scan 192.168.29.x for port 5555. USB debugging may be off; ask the user to turn it on.
- The only buttons that change behaviour are MENU (short and long press) and Red/Green/Yellow/Blue. Every other key passes through untouched unless the bar, a pane, the screen cover or the last-minute warning is up (see the spec's "Remote buttons").
- Backlight: 10 segments, steps of 10. Blue light: 4 segments, steps of 25% of its key range. Presets: Day 100/0, Evening 60/50, Night 20/75 (backlight / blue light %).
- Sleep steps: 10, 15, 30, 60, 90, 120 min. Reminders at 360, 120, 60, 30, 15, 5 min left, only those < the timer's original length. The last-minute extension is `min(15, original)`.
- Bar auto-closes after 8 s without a key press, except in edit mode. Normal pills last 2 s, sleep reminders 3 s, and the last-minute pill stays until answered or the timer ends.
- Copy is sentence case, e.g. "Sleeping in 1 min", "The TV will turn off", "Editing the bar · Back done", "Press OK", "Shown", "Hidden", "Moving", "OK ⋯".

## Review Focus

1. **Values set from the TV's own menu that fall between ticks.** Example: backlight 37. Up should go to 40 and Down to 30, and nothing should break. The test is in Task 2.
2. **A mode key holding a value the app doesn't know**, e.g. `picture_mode=7` after a firmware update. The tile shows "Mode 7"; Up moves to the last known option and Down to the first. The test is in Task 2.
3. **Holding a key down (auto-repeat).** Up/Down keep stepping, but holding MENU or a colour button fires the long-press action once and never toggles the bar. The test is in Task 5.
4. **Colour buttons pressed while the bar is open.** The preset applies and the open bar shows the new values right away. The test is in Task 5.
5. **Every tile hidden in edit mode.** The bar opens with only Edit, focus stays valid, and the layout survives a restart. The decoder appends tiles missing from saved data, so new tiles in a later version show up. The tests are in Tasks 4 and 6.

---

## Phase 0: TV tests (user present, TV free)

### Task 0: Learn the remote's keys and the setting keys

Nothing is built yet. This task fills in the spec's "Setting keys to confirm on the TV" table and decides which tiles survive.

**Files:**
- Create: `docs/haier-tests.md` (the findings, one section per item below)
- Modify: `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md` (the table gets a "Result" column, and dropped tiles are marked)
- Modify: `../tvs/haier/changes.md` (each write and restore)

- [ ] **Step 1: Ask the user whether the TV is free and USB debugging is on, then `adb connect 192.168.29.186:5555`.** Expected: `connected to …`.
- [ ] **Step 2: Remote key codes.** Run `adb shell getevent -l` and have the user press MENU (short, then held 2 s), Red, Green, Yellow, Blue, FAV and Up. Record each Linux key name. Find the remote's key layout file (`adb shell dumpsys input`, under the remote's device, "KeyLayoutFile:"), `adb pull` it, and record the Android keycode name for each key. Expected: MENU→`MENU`, colours→`PROG_RED/GREEN/YELLOW/BLUE`. If they differ, record the real codes; Task 5's `keyOf` uses this table.
- [ ] **Step 3: MENU long-press target.** Have the user long-press MENU, then run `adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"`. Record the component (`pkg/.Activity`) as `SETTINGS_COMPONENT`. Have the user press Back.
- [ ] **Step 4: Picture and sound mode names.** Have the user switch each picture mode in the TV menu; after each switch run `adb shell settings get global picture_mode`. Record the name → value pairs in menu order. Do the same for sound modes with `sound_style`.
- [ ] **Step 5: Live-write tests.** For each key below, read the old value, write a test value, ask the user what changed on screen or in sound, then restore the old value and log it. Record live / not live:
  - `picture_blue_light`: first have the user set blue light to its maximum in the TV menu and read the key. That reading is the **max** (the range). Then restore.
  - `picture_mode`: write a different mode value.
  - `sound_style`: same.
  - `sound_dialog_enhancement`: 0↔1.
  - `picture_backlight` per input: set it to 50 in an app, have the user switch to HDMI and back, and read it each time. Record whether HDMI keeps its own value. Then set a different picture mode and read it again to see whether the value is kept per mode.
- [ ] **Step 6: Find the hidden keys by diffing.** For sleep timer, sound output and "picture off": run `adb shell settings list global > a.txt` (also `system` and `secure`). Have the user change that feature in the TV menu, run the lists again into `b.txt`, `diff` them, and record the changed keys. If the user sets the TV's own sleep timer to 10 min, record its key and value. Have the user cancel it afterwards. Then write each key found back and forth once to confirm it works live, and restore it.
- [ ] **Step 7: Screensaver.** Ask the user whether any remote button or combination already starts the screensaver. If yes, mark Ambient as dropped. Otherwise run `adb shell am start -n com.android.systemui/.Somnambulator`. Expected: the screensaver starts. If it doesn't, record the error and try `adb shell cmd dreams start-dreaming` / `adb shell service call dreams 1` for information. The app can only use the activity route, so if the activity fails, Ambient is dropped.
- [ ] **Step 8: Write up.** In `docs/haier-tests.md`, record the key codes, `SETTINGS_COMPONENT`, mode name/value lists, the blue-light max, the sleep/output/picture-off keys, and the backlight-per-input result. In the spec table, add a Result column and mark each tile *keep*, *fallback: open <component>* or *dropped*. **Ask the user to confirm the final tile list before Task 2.**
- [ ] **Step 9: Commit**

```bash
git add docs/haier-tests.md docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md
git commit -m "TV tests: key codes, setting keys, tile list"
```

---

## Phase 1: Build

### Task 1: Toolchain and empty app that builds

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/quick_service.xml`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/QuickService.kt` (empty service for now)
- Create: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/SmokeTest.kt`
- Modify: `.gitignore` (already ignores `build/`, `.gradle/`, `local.properties`)

**Interfaces:**
- Produces: `class QuickService : AccessibilityService()` declared in the manifest; Task 6 fills it in.

- [ ] **Step 1: Install the JDK and Gradle** (the user has already agreed): `winget install Microsoft.OpenJDK.17` and `winget install Gradle.Gradle`. Check: `java -version` shows 17, and `gradle -v` works.
- [ ] **Step 2: Install the Android command-line tools.** Get the current "Command line tools only" Windows zip from developer.android.com/studio, and tell the user the file name and size before downloading. Extract it to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`. Run `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. **The SDK license prompt is the user's to accept**: show them `sdkmanager --licenses` and let them answer it. Don't pipe `yes` into it. Write `sdk.dir` to `local.properties`.
- [ ] **Step 3: Write the Gradle files.** Use the versions from Tech Stack. In `app/build.gradle.kts`: `namespace`/`applicationId` = `io.github.lettuceketchup.tvqs`, `minSdk = 30`, `targetSdk = 30`, `compileSdk = 34`, Kotlin `jvmTarget = "17"`, and `testImplementation("junit:junit:4.13.2")`. Then run `gradle wrapper --gradle-version 8.7`.
- [ ] **Step 4: Write the manifest and the service config.**
  - The manifest declares `android.permission.WRITE_SECURE_SETTINGS` (granted later over adb), `<uses-feature android:name="android.software.leanback" android:required="false"/>`, no launcher activity, and the service with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, an intent filter on `android.accessibilityservice.AccessibilityService`, and meta-data pointing at `@xml/quick_service`.
  - `quick_service.xml` sets `android:accessibilityFlags="flagRequestFilterKeyEvents"`, `android:canRequestFilterKeyEvents="true"`, `android:accessibilityEventTypes="typeWindowStateChanged"`, `android:canRetrieveWindowContent="false"` and `android:description="@string/service_description"`.
- [ ] **Step 5: Write `SmokeTest`** with one `@Test fun builds() = assertEquals(4, 2 + 2)`. This proves the test task runs.
- [ ] **Step 6: Build.** Run `./gradlew assembleDebug testDebugUnitTest`. Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 7: Commit** (the wrapper jar included):

```bash
git add -A && git commit -m "Build setup: Gradle, manifest, empty accessibility service"
```

### Task 2: Tiles, steps and presets (pure logic)

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Tiles.kt`
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Haier.kt` (the setting keys, mode lists, blue-light max and `SETTINGS_COMPONENT` from Task 0, as constants)
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/TilesTest.kt` (delete `SmokeTest.kt`)

**Interfaces:**
- Produces:
  - `enum class TileId { BACKLIGHT, BLUE_LIGHT, PRESET, PICTURE, SOUND, DIALOGUE, OUTPUT, SLEEP, AMBIENT, SCREEN_OFF, EDIT }`
  - `data class Option(val label: String, val value: Int)`
  - `sealed interface Tile { val id: TileId; val label: String }`, with these implementations:
    - `data class LevelTile(id, label, key: String, segments: Int, max: Int)`. The controller holds **percent** values (0–100); `fun toRaw(percent: Int): Int = Math.round(percent * max / 100f)` and `fun toPercent(raw: Int): Int` convert.
    - `data class ModeTile(id, label, key: String?, options: List<Option>, hasPane: Boolean)`. A `null` key is the Preset tile, which writes through presets.
    - `data class ActionTile(id, label, action: Action)`, where `enum class Action { SCREENSAVER, SCREEN_OFF, OPEN_ACTIVITY }` plus `val component: String? = null`.
    - `object SleepTile` and `object EditTile`, both implementing `Tile`.
  - `fun stepLevel(percent: Int, segments: Int, up: Boolean): Int`: move to the next tick in that direction, clamped to 0..100. Off-grid values snap to the neighbouring tick.
  - `fun stepMode(index: Int, size: Int, up: Boolean): Int?`: Up = index − 1 (the option shown above), with no wrap and `null` at an end. An unknown value (`index == -1`) goes Up to `size-1` and Down to `0`.
  - `fun wrapFocus(index: Int, count: Int, right: Boolean): Int`
  - `fun modeLabel(tile: ModeTile, value: Int): String`: the option label, or `"Mode $value"` when the value is unknown.
  - `data class Preset(val name: String, val backlight: Int, val blueLight: Int)` (both percent) and `val DEFAULT_PRESETS = listOf(Preset("Day",100,0), Preset("Evening",60,50), Preset("Night",20,75))`. The index order is Day=0, Evening=1, Night=2.
  - `fun defaultTiles(): Map<TileId, Tile>`, built from `Haier.kt`, holding only the tiles kept in Task 0.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun backlightSnapsUpFromOffGrid() = assertEquals(40, stepLevel(37, 10, up = true))
@Test fun backlightSnapsDownFromOffGrid() = assertEquals(30, stepLevel(37, 10, up = false))
@Test fun backlightClampsAtTop() = assertEquals(100, stepLevel(100, 10, up = true))
@Test fun blueLightQuarterSteps() { assertEquals(25, stepLevel(0, 4, true)); assertEquals(75, stepLevel(100, 4, false)) }
@Test fun modeUpAtFirstIsNull() = assertNull(stepMode(0, 2, up = true))
@Test fun modeDownAtLastIsNull() = assertNull(stepMode(1, 2, up = false))
@Test fun modeDownMovesToNext() = assertEquals(1, stepMode(0, 2, up = false))
@Test fun unknownModeValue() { assertEquals(4, stepMode(-1, 5, true)); assertEquals(0, stepMode(-1, 5, false)) }
@Test fun unknownModeLabel() = assertEquals("Mode 7", modeLabel(ModeTile(TileId.PICTURE, "Picture", "picture_mode", listOf(Option("Standard", 0)), true), 7))
@Test fun focusWrapsBothWays() { assertEquals(10, wrapFocus(0, 11, right = false)); assertEquals(0, wrapFocus(10, 11, right = true)) }
@Test fun blueLightRawRoundTrip() { val t = LevelTile(TileId.BLUE_LIGHT, "Blue light", "k", 4, max = 20); assertEquals(15, t.toRaw(75)); assertEquals(75, t.toPercent(15)) }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest`. Expected: FAIL, because the symbols are unresolved.
- [ ] **Step 3: Implement `Tiles.kt` and `Haier.kt`** with the signatures above.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Tiles, steps, presets"` (after adding the new files).

### Task 3: Sleep timer maths (pure)

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/SleepMath.kt`
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/SleepMathTest.kt`

**Interfaces:**
- Produces:
  - `val SLEEP_STEPS = listOf(10, 15, 30, 60, 90, 120)`
  - `fun minutesLeft(msLeft: Long): Int`: rounds up, so 61 s gives 2.
  - `fun nextLonger(leftMin: Int): Int?`: the first step > `leftMin` (`0` = off, which gives 10), or `null` above 120.
  - `fun nextShorter(leftMin: Int): Int?`: the largest step < `leftMin`. Returns `0` (Off) when no step is smaller, and `null` when the timer is off.
  - `fun reminderCrossed(prevMsLeft: Long, nowMsLeft: Long, startedMin: Int): Int?`: the milestone in `[360,120,60,30,15,5]` passed between the two times (prev > m·60000 ≥ now) with `m < startedMin`, otherwise `null`.
  - `fun lastMinute(msLeft: Long): Boolean = msLeft in 1..60_000`
  - `fun extensionMin(startedMin: Int): Int = minOf(15, startedMin)`
  - `fun fmtMinutes(min: Int): String`: `"23 min"`, `"1 h"`, `"1 h 30 min"`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun longerFromOff() = assertEquals(10, nextLonger(0))
@Test fun longerFromRunning() = assertEquals(30, nextLonger(23))
@Test fun longerAtTop() = assertNull(nextLonger(120))
@Test fun shorterFromRunning() = assertEquals(15, nextShorter(23))
@Test fun shorterBelowSmallestIsOff() = assertEquals(0, nextShorter(8))
@Test fun shorterWhenOff() = assertNull(nextShorter(0))
@Test fun reminderAt30() = assertEquals(30, reminderCrossed(31 * 60_000L, 29 * 60_000L, startedMin = 60))
@Test fun noReminderEqualToStart() = assertNull(reminderCrossed(31 * 60_000L, 29 * 60_000L, startedMin = 30))
@Test fun extensionShortTimer() = assertEquals(10, extensionMin(10))
@Test fun extensionLongTimer() = assertEquals(15, extensionMin(60))
@Test fun roundsUp() = assertEquals(2, minutesLeft(61_000))
@Test fun formats() { assertEquals("23 min", fmtMinutes(23)); assertEquals("1 h", fmtMinutes(60)); assertEquals("1 h 30 min", fmtMinutes(90)) }
```

- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `SleepMath.kt`.**
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `"Sleep timer maths"`.

### Task 4: Bar layout: order, hidden tiles, persistence (pure)

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/BarLayout.kt`
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/BarLayoutTest.kt`

**Interfaces:**
- Consumes: `TileId` (Task 2).
- Produces:
  - `data class BarLayout(val order: List<TileId>, val hidden: Set<TileId>)`. `order` never contains `EDIT`.
  - `fun BarLayout.visible(): List<TileId>`: the shown tiles in order, then `EDIT`.
  - `fun BarLayout.all(): List<TileId>`: every tile in order, for edit mode, without `EDIT`.
  - `fun BarLayout.toggle(id: TileId): BarLayout`
  - `fun BarLayout.move(id: TileId, right: Boolean): BarLayout`: swaps with the neighbour and leaves the layout unchanged at an end.
  - `fun BarLayout.encode(): String`: e.g. `"BACKLIGHT,BLUE_LIGHT,-OUTPUT,…"`, where `-` marks hidden.
  - `fun decodeBarLayout(s: String?, known: List<TileId>): BarLayout`: `null` or garbage gives the default (`known` in order, none hidden). Unknown names are dropped, and known ids missing from the string are appended as shown.

- [ ] **Step 1: Write the failing tests**

```kotlin
val known = listOf(TileId.BACKLIGHT, TileId.BLUE_LIGHT, TileId.OUTPUT)
@Test fun visibleEndsWithEdit() = assertEquals(listOf(TileId.BACKLIGHT, TileId.BLUE_LIGHT, TileId.OUTPUT, TileId.EDIT), decodeBarLayout(null, known).visible())
@Test fun allHiddenLeavesEdit() { var l = decodeBarLayout(null, known); known.forEach { l = l.toggle(it) }; assertEquals(listOf(TileId.EDIT), l.visible()) }
@Test fun moveStopsAtEnd() { val l = decodeBarLayout(null, known); assertEquals(l, l.move(TileId.BACKLIGHT, right = false)) }
@Test fun moveSwaps() = assertEquals(listOf(TileId.BLUE_LIGHT, TileId.BACKLIGHT, TileId.OUTPUT), decodeBarLayout(null, known).move(TileId.BACKLIGHT, true).order)
@Test fun roundTrip() { val l = decodeBarLayout(null, known).toggle(TileId.OUTPUT).move(TileId.OUTPUT, false); assertEquals(l, decodeBarLayout(l.encode(), known)) }
@Test fun newTileAppended() = assertEquals(known, decodeBarLayout("BACKLIGHT,BLUE_LIGHT", known).order)
@Test fun garbageGivesDefault() = assertEquals(decodeBarLayout(null, known), decodeBarLayout("%%,NOPE", known))
```

- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `BarLayout.kt`.**
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `"Bar layout and persistence format"`.

### Task 5: Controller: key routing and UI state (pure)

This is the core. The service sends every key here and acts on the returned effects.

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Controller.kt`
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/ControllerTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–4.
- Produces:
  - `enum class Key { MENU, UP, DOWN, LEFT, RIGHT, OK, BACK, RED, GREEN, YELLOW, BLUE, OTHER }`, and `fun keyOf(keyCode: Int): Key`, which maps the `android.view.KeyEvent.KEYCODE_*` values recorded in Task 0, with `DPAD_CENTER` and `ENTER` both mapping to `OK`.
  - `sealed interface Effect` with:
    - `Write(key: String, raw: Int)`
    - `Pill(title: String, detail: String, levelPercent: Int?, millis: Long)`, where `millis = 0` means it stays up
    - `HidePill`
    - `OpenActivity(component: String)`
    - `StartScreensaver`, `ScreenOff`, `ScreenOn`
    - `SetSleep(minutes: Int)` (0 = cancel), `ExtendSleep(minutes: Int)`
    - `SaveLayout(layout: BarLayout)`, `SavePresets(presets: List<Preset>)`
  - `class Controller(tiles: Map<TileId, Tile>, layout: BarLayout, presets: List<Preset>, settingsComponent: String)` with:
    - `fun onKey(key: Key, down: Boolean, repeat: Int): Boolean`: returns consumed. Effects are queued into `val effects: MutableList<Effect>`, which the service drains after each call.
    - `fun refresh(values: Map<TileId, Int>)`: percent values for level tiles, raw values for mode tiles.
    - `var sleepLeftMin: Int` and `var sleepStartedMin: Int`, set by the service.
    - `fun showSleepWarning()` and `fun clearSleepWarning()`
    - `fun timeoutClose()`: closes the bar unless editing.
    - `val state: UiState`, where `data class UiState(val barOpen: Boolean, val tiles: List<TileId>, val focus: Int, val paneIndex: Int?, val editing: Boolean, val held: Boolean, val covered: Boolean, val warning: Boolean)`.
    - `fun valueOf(id: TileId): Int?`, used by the view.
- Routing rules, in priority order:
  1. If `covered` (screen off): the next DOWN emits `ScreenOn`, and both that DOWN and its UP are consumed.
  2. If `warning`: OK emits `ExtendSleep(extensionMin(sleepStartedMin))` + `HidePill`; BACK emits `HidePill`. Both are consumed, and both clear `warning`.
  3. MENU and colour keys use **press tracking**: DOWN with repeat 0 is consumed and nothing happens yet. The first DOWN with repeat ≥ 1 fires the long action once (MENU → `OpenActivity(settingsComponent)`; Red/Green/Yellow → save the current backlight/blue into Night/Evening/Day, then `SavePresets` + `Pill("Saved to Night", "Backlight 20 · Blue light 75", 20, 2000)`). Later repeats are consumed and do nothing. On UP, if no long action fired, the short action runs: MENU toggles the bar (on close, also closes the pane and leaves edit mode). Red/Green/Yellow apply the Night/Evening/Day preset: `Write` for both levels, update the values, then `Pill(name, "Backlight X · Blue light Y", X, 2000)`. Blue closes the bar, sets `covered` and emits `ScreenOff`.
  4. If the bar is closed: return `false` for everything else.
  5. Bar open (consume DOWN and UP for UP/DOWN/LEFT/RIGHT/OK/BACK; let OTHER through), with the pane, edit mode or normal mode taking the keys as described in the spec's "The bar", "Tile types", "Editing the bar" and "Sleep timer":
     - Level Up/Down → `stepLevel` then `Write(key, toRaw)`.
     - Mode Up/Down → `stepMode` then `Write`. Preset writes both levels.
     - Sleep Up → `SetSleep(nextLonger)`; Down → `SetSleep(nextShorter)`.
     - OK on a mode with a pane opens the pane. OK on an action emits its effect and closes the bar. OK on Edit enters edit mode with focus 0.
     - The pane: Up/Down move the highlight (clamped), OK writes and closes the pane, BACK closes the pane.
     - Edit mode: Up/Down toggle (`SaveLayout`), OK holds/drops, held Left/Right `move`, BACK exits to focus on Edit.
     - Normal BACK closes the bar.
     - UP/DOWN auto-repeat (repeat > 0) acts like separate presses.

- [ ] **Step 1: Write the failing tests.** Use a helper `fun Controller.press(k: Key) { onKey(k, true, 0); onKey(k, false, 0) }` and `fun Controller.hold(k: Key) { onKey(k, true, 0); onKey(k, true, 1); onKey(k, true, 2); onKey(k, false, 0) }`. Build `defaultTiles()` with a test `BarLayout`, and `refresh` backlight 37, blue 0, picture 0.

```kotlin
@Test fun menuShortOpensBar() { c.press(Key.MENU); assertTrue(c.state.barOpen) }
@Test fun menuHoldOpensSettingsOnceAndNotBar() { c.hold(Key.MENU); assertFalse(c.state.barOpen); assertEquals(1, c.effects.count { it == Effect.OpenActivity(SETTINGS_COMPONENT) }) }
@Test fun otherKeysPassWhenClosed() = assertFalse(c.onKey(Key.UP, true, 0))
@Test fun leftFromFirstGoesToEdit() { c.press(Key.MENU); c.press(Key.LEFT); assertEquals(TileId.EDIT, c.state.tiles[c.state.focus]) }
@Test fun upOnBacklightWritesNextTick() { c.press(Key.MENU); c.press(Key.UP); assertTrue(Effect.Write("picture_backlight", 40) in c.effects) }
@Test fun upRepeatKeepsStepping() { c.press(Key.MENU); c.onKey(Key.UP, true, 0); c.onKey(Key.UP, true, 1); assertEquals(50, c.valueOf(TileId.BACKLIGHT)) }
@Test fun okOnPictureOpensPaneButNotOnDialogue() { /* focus PICTURE → OK → paneIndex == current; focus DIALOGUE → OK → paneIndex == null */ }
@Test fun panePickWritesAndCloses() { /* PICTURE pane, DOWN, OK → Write(picture key, option[1].value), paneIndex null */ }
@Test fun backClosesPaneThenBar() { /* pane open → BACK → bar still open, pane null → BACK → bar closed */ }
@Test fun redAppliesNightWhileBarOpen() { c.press(Key.MENU); c.press(Key.RED); assertEquals(20, c.valueOf(TileId.BACKLIGHT)); assertTrue(c.state.barOpen); assertTrue(c.effects.any { it is Effect.Pill && it.title == "Night" }) }
@Test fun redHoldSavesIntoNight() { c.hold(Key.RED); assertTrue(c.effects.any { it is Effect.SavePresets && it.presets[2].backlight == 37 }) }
@Test fun blueCoversAndNextKeyIsSwallowed() { c.press(Key.BLUE); assertTrue(c.state.covered); assertTrue(c.onKey(Key.OK, true, 0)); assertTrue(c.onKey(Key.OK, false, 0)); assertTrue(Effect.ScreenOn in c.effects); assertFalse(c.state.covered) }
@Test fun warningOkExtendsByStart() { c.sleepStartedMin = 10; c.showSleepWarning(); c.press(Key.OK); assertTrue(Effect.ExtendSleep(10) in c.effects); assertFalse(c.state.warning) }
@Test fun warningBackOnlyHides() { c.showSleepWarning(); c.press(Key.BACK); assertTrue(Effect.HidePill in c.effects); assertFalse(c.effects.any { it is Effect.ExtendSleep }) }
@Test fun warningBeatsBar() { c.press(Key.MENU); c.showSleepWarning(); c.press(Key.BACK); assertTrue(c.state.barOpen) }
@Test fun sleepUpFromOff() { /* focus SLEEP, sleepLeftMin=0 → UP → SetSleep(10) */ }
@Test fun sleepDownBelowSmallestCancels() { /* sleepLeftMin=8 → DOWN → SetSleep(0) */ }
@Test fun editToggleAndMove() { /* OK on EDIT → editing, focus 0; DOWN → SaveLayout with tiles[0] hidden; OK, RIGHT, OK → order swapped; held LEFT at 0 → unchanged; BACK → not editing, focus on EDIT */ }
@Test fun timeoutIgnoredWhileEditing() { /* enter edit → timeoutClose() → barOpen still true */ }
@Test fun allHiddenFocusValid() { /* hide every tile, BACK out of edit → tiles == [EDIT], focus 0 */ }
```

  Write the four comment-only tests as real code. Each comment lists the exact keys and assertions.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `Controller.kt`** following the routing rules. Keep the set of DOWN keys it consumed, so each matching UP is consumed too.
- [ ] **Step 4: Run** the tests. Expected: all of Tasks 2–5 PASS.
- [ ] **Step 5: Commit** `"Controller: key routing, panes, presets, edit mode, warning"`.

### Task 6: Service wiring, settings I/O and the corner pill (first on-TV build)

**Files:**
- Modify: `QuickService.kt`
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Store.kt`: wraps SharedPreferences `"tvqs"`. Keys `"layout"` (`BarLayout.encode()`) and `"presets"` (`"Day:100:0;Evening:60:50;Night:20:75"`); `fun loadLayout(): BarLayout`, `fun saveLayout(l: BarLayout)`, `fun loadPresets(): List<Preset>`, `fun savePresets(p: List<Preset>)`. Bad data falls back to the defaults.
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/PillView.kt`: `class PillView(ctx: Context) : LinearLayout` with `fun show(p: Effect.Pill)`. The look is in the spec's "Remote buttons": top-right, about 75% black, rounded 14dp, icon, title, detail and a 4dp level bar.

**Interfaces:**
- Consumes: `Controller`, `Effect`, `keyOf`, `decodeBarLayout`, `defaultTiles`.
- Produces: `QuickService.overlay(view: View, gravity: Int): WindowManager.LayoutParams`, which Task 7 reuses. It creates `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN` and `PixelFormat.TRANSLUCENT`.

- [ ] **Step 1: Wire up `onKeyEvent`.** Map to `Key` and call `controller.onKey(key, event.action == ACTION_DOWN, event.repeatCount)`, then drain and perform the effects. The return value is what's consumed. Before the bar opens (or when a colour key fires), refresh the values from `Settings.Global.getInt(contentResolver, key, default)`. `Write` uses `Settings.Global.putInt`. Wrap each write in `try/catch (SecurityException)`; on failure, show a pill "Permission missing · Grant WRITE_SECURE_SETTINGS over adb" instead of crashing. `OpenActivity` starts the component with `FLAG_ACTIVITY_NEW_TASK`. Leave `ScreenOff`/`ScreenOn`/`StartScreensaver`/`SetSleep`/`ExtendSleep` as TODO no-ops for now; Tasks 8–9 fill them in.
- [ ] **Step 2: Build** with `./gradlew assembleDebug testDebugUnitTest`. Expected: success.
- [ ] **Step 3: Install on the TV (ask the user whether the TV is free first)**
  ```
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell pm grant io.github.lettuceketchup.tvqs android.permission.WRITE_SECURE_SETTINGS
  adb shell settings get secure enabled_accessibility_services
  ```
  Save the old value in `changes.md`. Then append `io.github.lettuceketchup.tvqs/.QuickService` (`:`-separated) with `settings put secure enabled_accessibility_services …`, and run `settings put secure accessibility_enabled 1`.
- [ ] **Step 4: Check on the TV.** In YouTube, the user presses Red, then Yellow. Expected: the screen dims, the pill appears top right, and `adb exec-out screencap -p > shot.png` shows it. Look at the screenshot. The user then long-presses MENU. Expected: the same Settings screen as before. This also confirms the app receives MENU before the firmware; if it doesn't, switch MENU to FAV in `keyOf` as the spec says, and tell the user.
- [ ] **Step 5: Log** the install, the grant and the accessibility change in `../tvs/haier/changes.md`. **Commit** `"Service: settings I/O, presets on colour keys, corner pill"`.

### Task 7: The bar view

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/BarView.kt`: `class BarView(ctx: Context) : FrameLayout` with `fun render(state: UiState, c: Controller, tiles: Map<TileId, Tile>)`.
- Modify: `QuickService.kt`: add the bar overlay at `Gravity.BOTTOM`, full width, and re-render after every handled key. Use a `Handler` for the 8 s `timeoutClose()`, restarting it on each key.

**What the view must show** (everything comes from the spec's "The bar" and "Tile types"):
- Dark translucent strip, about 60% black, rounded 13dp, inset about 3% from the sides and 5% from the bottom. A horizontal row of tiles, with the focused one scrolled into view (centred when possible) and chevrons at the ends when there's more.
- Unfocused tiles: about 12% white, a white hairline border, white text, the icon and name on top, the value at the bottom. Focused tile: solid white with dark text, about 1.3× the width and about 1.15× the height. Size the base width so the longest tile name fits at the TV's font size; the focused tile must never clip.
- Level tiles show the number plus a vertical segmented bar (`segments` blocks). When focused, ▲ appears above and ▼ below, each hidden at its limit. Mode and sleep tiles, when focused, show three lines: ▲ previous / current / ▼ next, with the empty end blank. Picture/Sound/Output show "OK ⋯". Action tiles show "Press OK". The list pane sits above the focused tile, with a tick on the current option and the highlight inverted.
- Edit mode: every tile, with hidden ones faded (about 40% opacity), a dashed border and an eye-off mark. A focused hidden tile is greyed (about 55% white with grey text). The held tile lifts about 8dp and says "Moving". A label above the bar reads "Editing the bar · Back done".
- Icons: use vector drawables you draw yourself in `res/drawable/` (simple strokes), or a Unicode glyph where a drawable isn't worth it. Don't add an icon library.

- [ ] **Step 1: Implement `BarView`** to the rules above.
- [ ] **Step 2: Build and install** (TV free?), and walk through these with the user. Take a screencap after each, check it, and fix anything that differs from the spec before moving on:
  1. MENU opens the bar.
  2. Up/Down on Backlight change the backlight live.
  3. Left from the first tile goes to Edit.
  4. OK on Picture shows the pane.
  5. Edit: hide Output, then move Sleep.
  6. MENU closes the bar. After a reboot the layout is kept.
  7. After 8 s idle, the bar closes.
- [ ] **Step 3: Commit** `"Bar view"`.

### Task 8: Sleep timer runtime and standby

**Files:**
- Modify: `QuickService.kt`

The approach depends on Task 0:
- **If Task 0 found a Haier sleep-timer key** that counts down by itself, `SetSleep` writes it, and the app still runs its own `Handler` countdown for display and reminders. The TV does the actual turn-off. `ExtendSleep` rewrites the key with the new remaining time.
- **If not,** the app turns the TV off at zero with `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`. Check on the TV that this puts it into standby. If it doesn't, stop and ask the user: the Sleep tile is dropped or opens the TV's sleep timer screen.

- [ ] **Step 1: Implement the countdown.** Store `endAt = SystemClock.elapsedRealtime() + min·60000` and `started`. Tick every 15 s, plus exactly at the 1-min mark. On each tick:
  - Set `controller.sleepLeftMin = minutesLeft(…)`.
  - If `reminderCrossed(prev, now, started)` returns a value, show `Pill("Sleeping in ${fmtMinutes(m)}", "Sleep timer", null, 3000)`.
  - If `lastMinute(now)` is true and the warning isn't up yet, call `controller.showSleepWarning()` and show `Pill("Sleeping in 1 min", "The TV will turn off", null, 0)` with the OK/Back choices drawn as key caps ("OK +N min", "Back Dismiss"), N = `extensionMin(started)`.
  - At zero, run the standby method, then `clearSleepWarning()` and reset.

  `SetSleep(0)` cancels everything. The countdown lives in memory: if the service restarts, the timer is gone (*ponytail: in-memory timer; persist `endAt` if restarts turn out to happen*).
- [ ] **Step 2: Verify on the TV** (TV free?).
  - Set a 10-min sleep. The tile shows the minutes left, and Up shows 15 min.
  - To test without waiting, add a debug-only broadcast receiver: `adb shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_SLEEP --ei ms 65000` sets the remaining time.
  - The last-minute pill appears. OK gives "+10 min" and the countdown jumps. Run it again, press Back, and the TV goes to standby at zero.
  - Remove the debug receiver from the release build: register it in code only when `BuildConfig.DEBUG`.
- [ ] **Step 3: Commit** `"Sleep timer runtime and standby"`.

### Task 9: Screen off and Ambient

**Files:**
- Modify: `QuickService.kt`

- [ ] **Step 1: Implement `ScreenOff`/`ScreenOn`.**
  - **If Task 0 found a working picture-off key**, write it, and write its normal value back on `ScreenOn`.
  - **Otherwise**, add a full-screen black overlay with the same overlay flags, remember the backlight, and write `picture_backlight` = 0. `ScreenOn` removes the overlay and restores the backlight.
  - In both cases the waking key is already swallowed by `Controller` (Task 5).
- [ ] **Step 2: Implement `StartScreensaver`** with `OpenActivity("com.android.systemui/.Somnambulator")`, unless Task 0 dropped Ambient.
- [ ] **Step 3: Verify on the TV** (TV free?), playing YouTube with sound:
  1. Blue turns the picture off and the sound continues.
  2. OK turns the picture back on without pausing the video.
  3. The Ambient tile starts the screensaver, and any button returns.
- [ ] **Step 4: Commit** `"Screen off and ambient"`.

### Task 10: Release build, setup notes, hand-over

**Files:**
- Modify: `README.md`: the "Build environment" becomes "Build and install", with the exact commands from Tasks 1 and 6 and how to undo (turn the service off with `settings put secure enabled_accessibility_services <old value>`, or `adb uninstall io.github.lettuceketchup.tvqs`).
- Modify: `CLAUDE.md`: Next steps.
- Modify: `../DEVICES.md`: fill in any TBD the tests answered (key layout, remote model HTR-U27E).

- [ ] **Step 1: Run** `./gradlew testDebugUnitTest assembleRelease` with a local debug-signed release (`signingConfig = signingConfigs.getByName("debug")`, since the keystore is gitignored). Expected: tests PASS and the APK is built.
- [ ] **Step 2: Install the release APK on the TV** (TV free?), and check the grant and the accessibility service again.
- [ ] **Step 3: Full check with the user.** Have them run through the spec's "Remote buttons" table and every tile once. Then turn USB debugging off, reboot, and confirm that MENU, the colour keys and the sleep timer still work.
- [ ] **Step 4: Log** the final state in `../tvs/haier/changes.md`, and **commit** `"Release build and setup notes"`. Push `main` to `lettuceketchup/tv-quick-settings` once the user approves.
