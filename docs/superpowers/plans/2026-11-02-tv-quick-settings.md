# TV Quick Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Android TV app for the Haier. In any app, MENU opens a translucent bar of picture/sound tiles, and the colour buttons apply Day/Evening/Night presets or turn the screen off. The app works with USB debugging off.

**Architecture:** One accessibility service filters remote key events and draws two accessibility-overlay windows: the bar and the corner pill. All behaviour lives in a pure Kotlin `Controller` state machine. It takes keys and a snapshot of setting values, and returns `Effect`s. The service performs those effects: writing `Settings.Global`, showing the pill, starting activities, running the sleep timer. The pure logic is covered by JVM unit tests. Views and Android calls are first shown on an Android TV emulator as a recorded, scripted demo (Task 5a), then checked on the TV with `adb exec-out screencap`.

**Tech Stack:** Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Gradle 8.7, JDK 17, compileSdk 34, minSdk/targetSdk 30 (the TV runs Android 11). Programmatic Android Views, with no layout XML beyond the service config. No runtime libraries. JUnit 4.13.2 for tests only. No global Gradle: the repo carries the Gradle 8.7 wrapper (jar checksum-verified, distribution pinned with `distributionSha256Sum`).

**Spec:** `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md`. Read it first; this plan refers to its sections by name.

## Global Constraints

- Package `io.github.lettuceketchup.tvqs`, app label "TV Quick Settings".
- No runtime dependencies. The only test dependency is `junit:junit:4.13.2`.
- Work on the TV is reversible only. Every setting written during tests is restored and logged in `../tvs/haier/changes.md` (date, key, old → new → restored, result).
- **Never operate the TV while someone is watching.** Before every adb step that changes what's on screen, ask the user whether the TV is free. Read-only commands (`settings get`, `dumpsys`, `getevent`) are fine at any time, but `getevent` needs the user to press buttons.
- Haier adb: `adb connect 192.168.29.186:5555`. The IP isn't reserved; if that fails, scan 192.168.29.x for port 5555. USB debugging may be off; ask the user to turn it on.
- The only buttons that change behaviour are MENU (short press; a held MENU does nothing, because the remote's own long-press key opens Settings) and Red/Green/Yellow/Blue. Every other key passes through untouched unless the bar, a pane, the screen cover or the last-minute warning is up (see the spec's "Remote buttons").
- Backlight: 10 segments, steps of 10. Presets: Day 100/Standard, Evening 60/Warm, Night 20/Warm (backlight % / warmth).
- **Task 0 is done** (`docs/haier-tests.md`, and the Result column in the spec's "Setting keys" table). Tiles: Backlight, Warmth, Preset, Picture, Sound, Output, Sleep, Screen off, in that fixed order. Blue light, Dialogue and Ambient were dropped, and the plan review dropped Edit (edit mode). Mode keys take **only** the values listed there: an unlisted `picture_mode` value corrupted the User mode on the TV.
- Sleep steps: 10, 15, 30, 60, 90, 120 min. Reminders at 60, 30, 15, 5 min left, only those < the timer's original length. Times are always shown in minutes ("90 min"). The last-minute extension is `min(15, original)`, and extending doesn't change the original length.
- **Key events carry no repeats.** An accessibility service sees keys before Android synthesises key repeats, so it most likely gets one DOWN and one UP per press (Task 1's key log checks this; if repeats do arrive, nothing below breaks). Long presses are detected from how long the key was held (`event.eventTime - event.downTime` on UP, long = ≥ 1000 ms), never from `repeatCount`. Holding Up/Down steps once.
- **The remote caps a hold at 3 s and then sends its own long-press key** (Task 1 key log): MENU → 250 (opens Settings), Red → 130 `MEDIA_RECORD`, Green → 127 `MEDIA_PAUSE`, Yellow → 86 `MEDIA_STOP`, Blue → 217 `RO` (opens Magic Board). The follow-up is a 1 ms press sent right after the UP, so no clock is needed: if the key event just before it was a colour UP with a hold ≥ 1000 ms, the Controller consumes the follow-up's DOWN and UP (Task 5, rule 0). MENU's 250 still passes through. Keycodes: MENU 82, colours 183–186, FAV 93.
- Bar auto-closes after 8 s without a key press. Normal pills last 2 s, sleep reminders 3 s, and the last-minute pill stays until answered or the timer ends.
- Copy is sentence case, e.g. "Sleeping in 1 min", "The TV will turn off", "Press OK", "OK ⋯".
- Plan review (2026-11-02): `docs/superpowers/reviews/2026-11-02-review-pass1.md` to `-pass3.md` explain the changes below (H1–H4, M1–M4, P1–P4 refer to them).
- **Emulator demo before the TV** (user, 2026-11-02): every task that changes what's on screen (6, 7, 8) runs its scripted demo on the `haier_tv` emulator after the build and before the TV install, and sends the user the video and screenshots with SendUserFile. The emulator is not a Haier: the Haier setting keys are seeded so the app reads sane values, and writes land in `Settings.Global` (the demo log shows them). From Task 6, a debug-only `SimView` imitates backlight, warmth and picture-off on a grey backdrop; it's an approximation of the panel, so say so with each demo. The panel is 4K, but Android draws at 1920×1080 (the only display mode, read from the TV 2026-11-02) and the TV upscales it 2×, so the emulator's layout matches but its text looks sharper than the TV's. Emulator commands always use `adb -s emulator-5554`, **never `adb -e`** (that means "the TCP/IP device", which is the TV when it's connected). Scripted keys go through `DEBUG_KEY` (Task 5a), so a demo doesn't exercise the key filter or pass-through; the TV checks do. The emulator itself, `avdmanager` and `sdkmanager` run in the user's terminal tab (AppData redirect, see Task 1 Step 2).

## Review Focus

1. **Values set from the TV's own menu that fall between ticks.** Example: backlight 37. Up should go to 40 and Down to 30, and nothing should break. The test is in Task 2.
2. **A mode key holding a value the app doesn't know**, e.g. `picture_mode=5` after a firmware update. The tile shows "Mode 7"; Up moves to the last known option and Down to the first. The test is in Task 2.
3. **Holding a key down.** Holding MENU (the remote keeps it down about 3 s, then sends its own Settings key) never toggles the bar. Holding a colour button saves the preset once, on release, and doesn't also apply it. A hold of 3 s or more also swallows the remote's follow-up key, and a held Blue stays covered. The tests are in Task 5.
4. **Colour buttons pressed while the bar is open.** The preset applies and the open bar shows the new values right away. The test is in Task 5.
5. **Values the TV changes by itself.** After a Picture switch the bar re-reads backlight and warmth (the TV keeps them per mode). Turning the TV off with the power button cancels the sleep timer and clears the screen cover, but Screen off doesn't. Checked on the TV in Tasks 6–8.

---

## Phase 0: TV tests (user present, TV free)

### Task 0: Learn the remote's keys and the setting keys

Nothing is built yet. This task fills in the spec's "Setting keys to confirm on the TV" table and decides which tiles survive.

**Done on 2026-11-02.** The steps below are kept as a record. Results are in `docs/haier-tests.md`. Where they differ from what the steps expected: the key layout file can't be read, so the Android keycodes are logged by the app in Task 6. A long MENU press is a separate remote key, so there's no `SETTINGS_COMPONENT` to open. The blue-light key does nothing, so the Warmth tile (colour temperature) replaces it.

**Files:**
- Create: `docs/haier-tests.md` (the findings, one section per item below)
- Modify: `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md` (the table gets a "Result" column, and dropped tiles are marked)
- Modify: `../tvs/haier/changes.md` (each write and restore)

- [x] **Step 1: Ask the user whether the TV is free and USB debugging is on, then `adb connect 192.168.29.186:5555`.** Expected: `connected to …`.
- [x] **Step 2: Remote key codes.** Run `adb shell getevent -l` and have the user press MENU (short, then held 2 s), Red, Green, Yellow, Blue, FAV and Up. Record each Linux key name. Find the remote's key layout file (`adb shell dumpsys input`, under the remote's device, "KeyLayoutFile:"), `adb pull` it, and record the Android keycode name for each key. Expected: MENU→`MENU`, colours→`PROG_RED/GREEN/YELLOW/BLUE`. If they differ, record the real codes; Task 5's `keyOf` uses this table.
- [x] **Step 3: MENU long-press target.** Have the user long-press MENU, then run `adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"`. Record the component (`pkg/.Activity`) as `SETTINGS_COMPONENT`. Have the user press Back.
- [x] **Step 4: Picture and sound mode names.** Have the user switch each picture mode in the TV menu; after each switch run `adb shell settings get global picture_mode`. Record the name → value pairs in menu order. Do the same for sound modes with `sound_style`.
- [x] **Step 5: Live-write tests.** For each key below, read the old value, write a test value, ask the user what changed on screen or in sound, then restore the old value and log it. Record live / not live:
  - `picture_blue_light`: first have the user set blue light to its maximum in the TV menu and read the key. That reading is the **max** (the range). Then restore.
  - `picture_mode`: write a different mode value.
  - `sound_style`: same.
  - `sound_dialog_enhancement`: 0↔1.
  - `picture_backlight` per input: set it to 50 in an app, have the user switch to HDMI and back, and read it each time. Record whether HDMI keeps its own value. Then set a different picture mode and read it again to see whether the value is kept per mode.
- [x] **Step 6: Find the hidden keys by diffing.** For sleep timer, sound output and "picture off": run `adb shell settings list global > a.txt` (also `system` and `secure`). Have the user change that feature in the TV menu, run the lists again into `b.txt`, `diff` them, and record the changed keys. If the user sets the TV's own sleep timer to 10 min, record its key and value. Have the user cancel it afterwards. Then write each key found back and forth once to confirm it works live, and restore it.
- [x] **Step 7: Screensaver.** Ask the user whether any remote button or combination already starts the screensaver. If yes, mark Ambient as dropped. Otherwise run `adb shell am start -n com.android.systemui/.Somnambulator`. Expected: the screensaver starts. If it doesn't, record the error and try `adb shell cmd dreams start-dreaming` / `adb shell service call dreams 1` for information. The app can only use the activity route, so if the activity fails, Ambient is dropped.
- [x] **Step 8: Write up.** In `docs/haier-tests.md`, record the key codes, `SETTINGS_COMPONENT`, mode name/value lists, the blue-light max, the sleep/output/picture-off keys, and the backlight-per-input result. In the spec table, add a Result column and mark each tile *keep*, *fallback: open <component>* or *dropped*. **Ask the user to confirm the final tile list before Task 2.**
- [x] **Step 9: Commit**

```bash
git add docs/haier-tests.md docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md
git commit -m "TV tests: key codes, setting keys, tile list"
```

---

## Phase 1: Build

### Task 1: Toolchain, and a key-logging service on the TV

The first build is a probe: a service that logs every key and lets it through, plus a debug broadcast that tries the standby method. It answers the questions Task 5 depends on (the real keycodes, whether MENU reaches the app, whether repeats arrive, whether the TV can be put in standby) before any logic is written.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/quick_service.xml`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/QuickService.kt`
- Modify: `docs/haier-tests.md` (the key log and standby results)
- Modify: `../tvs/haier/changes.md` (install, grant, accessibility change)
- Already there: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`; `.gitignore` ignores `build/`, `.gradle/`, `local.properties`

**Interfaces:**
- Produces: `class QuickService : AccessibilityService()` declared in the manifest; Task 6 fills it in.

- [x] **Step 1: Install the JDK.** Done 2026-11-02: `winget install Microsoft.OpenJDK.17` (`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`, `JAVA_HOME` set machine-wide). There's no Gradle package in winget, so the repo carries the Gradle 8.7 wrapper instead (fetched from Gradle's `v8.7.0` tag, jar checksum checked).
- [x] **Step 2: Install the Android SDK.** Done 2026-11-02: command-line tools `commandlinetools-win-15859902_latest.zip` in `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, the user accepted the licences, and `platforms;android-34` + `build-tools;34.0.0` are installed. `local.properties` has `sdk.dir`. Anything that writes to the SDK must run in the user's terminal tab: folders Claude's tools create under AppData\Local are redirected and invisible to the user's shell.
- [x] **Step 3: Write the Gradle files.** Use the versions from Tech Stack. In `app/build.gradle.kts`: `namespace`/`applicationId` = `io.github.lettuceketchup.tvqs`, `minSdk = 30`, `targetSdk = 30`, `compileSdk = 34`, Kotlin `jvmTarget = "17"`, `buildFeatures { buildConfig = true }` (AGP 8 doesn't generate `BuildConfig` by default, and Tasks 1, 6 and 8 use `BuildConfig.DEBUG`), and `testImplementation("junit:junit:4.13.2")`.
- [x] **Step 4: Write the manifest and the service config.**
  - The manifest declares `android.permission.WRITE_SECURE_SETTINGS` with `tools:ignore="ProtectedPermissions"` (granted later over adb), `<uses-feature android:name="android.software.leanback" android:required="false"/>`, no launcher activity, and the service with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, an intent filter on `android.accessibilityservice.AccessibilityService`, and meta-data pointing at `@xml/quick_service`.
  - `quick_service.xml` sets `android:accessibilityFlags="flagRequestFilterKeyEvents"`, `android:canRequestFilterKeyEvents="true"`, `android:canRetrieveWindowContent="false"` and `android:description="@string/service_description"`. No `accessibilityEventTypes`: the service uses no accessibility events.
- [x] **Step 5: Write the probe `QuickService`.**
  - `onKeyEvent` logs `Log.d("QuickService", …)` with `keyCode`, `scanCode`, `action`, `repeatCount`, `downTime` and `eventTime`, and returns `false` (everything passes through).
  - When `BuildConfig.DEBUG`, `onServiceConnected` registers a receiver for `io.github.lettuceketchup.tvqs.DEBUG_STANDBY` that calls `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`. Task 8 adds its sleep broadcast to the same receiver.
  - `onAccessibilityEvent` and `onInterrupt` are empty.
- [x] **Step 6: Build.** Run `./gradlew assembleDebug`. Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists. (There are no tests yet; Task 2's tests are the first.)
- [x] **Step 7: Install on the TV (ask the user whether the TV is free first).**
  ```
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell pm grant io.github.lettuceketchup.tvqs android.permission.WRITE_SECURE_SETTINGS
  adb shell settings get secure enabled_accessibility_services
  ```
  Save the old value in `changes.md`. Then append `io.github.lettuceketchup.tvqs/.QuickService` to it (`:`-separated, keeping Projectivy's entry) with `settings put secure enabled_accessibility_services …`, and run `settings put secure accessibility_enabled 1`.
- [x] **Step 8: Key log.** Run `adb logcat -s QuickService` while the user presses, in YouTube: MENU (short), MENU (held until Settings opens), Red, Green, Yellow, Blue (short, then each held 2 s), FAV, Up (held 2 s), OK and Back. Record in `docs/haier-tests.md`:
  - each button's `keyCode` and `scanCode`;
  - whether any DOWN arrives with `repeatCount` > 0 (expected: none);
  - the held MENU's DOWN → UP time and the keyCode of the Settings key that follows;
  - whether MENU shows up at all. If it doesn't, the firmware takes it first: FAV opens the bar instead (spec), and tell the user.
  - If the four colours share one keyCode, tell the user: the presets are then only reachable from the bar.
  Also check that Projectivy's Back double press still works with both services on.
- [x] **Step 9: Standby.** Ask whether the TV is free, then `adb shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_STANDBY`. Expected: the TV goes into standby as if power were pressed, and the remote's power button wakes it. Record the result. If it doesn't, try writing `Settings.Secure` `attentive_timeout` to a short value from the app as a second method, and restore it. If neither works, stop and ask the user: the Sleep tile is then dropped or opens the TV's own sleep timer screen.
- [x] **Step 10: Log** the install, the grant and the accessibility change in `../tvs/haier/changes.md`. **Commit** (the wrapper jar included):

```bash
git add -A && git update-index --chmod=+x gradlew && git commit -m "Build setup and key-logging probe service"
```

### Task 2: Tiles, steps and presets (pure logic)

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Tiles.kt`, including the Haier setting keys and value → name lists from `docs/haier-tests.md` as constants (one target, one file)
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/TilesTest.kt`

**Interfaces:**
- Produces:
  - `enum class TileId { BACKLIGHT, WARMTH, PRESET, PICTURE, SOUND, OUTPUT, SLEEP, SCREEN_OFF }`. The enum order is the bar's order; there's no edit mode.
  - `data class Option(val label: String, val value: Int)`
  - `sealed interface Tile { val id: TileId; val label: String }`, with these implementations:
    - `data class LevelTile(id, label, key: String)`: only Backlight. Values are 0–100, which is already `picture_backlight`'s raw range, so there's no conversion. `const val BACKLIGHT_STEP = 10` gives the 10 segments.
    - `data class ModeTile(id, label, key: String?, options: List<Option>, hasPane: Boolean)`. A `null` key is the Preset tile, which writes through presets.
    - `data class ActionTile(id, label)`: only Screen off, so no action enum.
    - `object SleepTile`, implementing `Tile`.
  - `fun stepLevel(percent: Int, step: Int, up: Boolean): Int`: move to the next multiple of `step` in that direction, clamped to 0..100. Off-grid values snap to the neighbouring tick.
  - `fun stepMode(index: Int, size: Int, up: Boolean): Int?`: Up = index − 1 (the option shown above), with no wrap and `null` at an end. An unknown value (`index == -1`) goes Up to `size-1` and Down to `0`.
  - `fun wrapFocus(index: Int, count: Int, right: Boolean): Int`
  - `fun modeLabel(tile: ModeTile, value: Int): String`: the option label, or `"Mode $value"` when the value is unknown. The Preset tile is the exception: an unknown value reads `"Custom"`.
  - `fun presetIndex(presets: List<Preset>, backlight: Int, warmth: Int): Int`: the index of the preset whose backlight **and** warmth both match the current values, or `-1` (shown as "Custom"). This is the Preset tile's value; it isn't stored anywhere. From Custom, Up goes to the last preset (Night) and Down to the first (Day), the same rule as any unknown mode value.
  - Haier mode lists (from `docs/haier-tests.md`): Picture `picture_mode` = User 0, Standard 7, Vivid 3, Sport 2, Movie 9, Game 10, Energy Saving 11 (menu order). Sound `sound_style` = User 0, Standard 1, Night 2, Sports 3, Movie 4, Music 5, News 6. Warmth `picture_color_temperature` = User 0, Cool 1, Standard 2, Warm 3 (no pane). Output `hdmi_system_audio_control_enabled` = TV speakers 0, External audio 1 (no pane). Screen off: `power_picture_off` 0 = off, 1 = on.
  - `data class Preset(val name: String, val backlight: Int, val warmth: Int)` (backlight 0–100, warmth a raw `picture_color_temperature` value) and `val DEFAULT_PRESETS = listOf(Preset("Day",100,2), Preset("Evening",60,3), Preset("Night",20,3))`. The index order is Day=0, Evening=1, Night=2.
  - `fun defaultTiles(): Map<TileId, Tile>`, built from the Haier constants, holding only the tiles kept in Task 0.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest`. Expected: FAIL, because the symbols are unresolved.
- [ ] **Step 3: Implement `Tiles.kt`** with the signatures above.
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
  - `fun remindersAhead(startedMin: Int, msLeft: Long): List<Int>`: the milestones in `[60,30,15,5]` still to come, i.e. `m < startedMin` and `m·60000 < msLeft`. Task 8 schedules one pill per milestone.
  - `fun extensionMin(startedMin: Int): Int = minOf(15, startedMin)`
  - Times are shown as `"$min min"` everywhere, so there's no formatting function.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun longerFromOff() = assertEquals(10, nextLonger(0))
@Test fun longerFromRunning() = assertEquals(30, nextLonger(23))
@Test fun longerAtTop() = assertNull(nextLonger(120))
@Test fun shorterFromRunning() = assertEquals(15, nextShorter(23))
@Test fun shorterBelowSmallestIsOff() = assertEquals(0, nextShorter(8))
@Test fun shorterWhenOff() = assertNull(nextShorter(0))
@Test fun remindersForAnHour() = assertEquals(listOf(30, 15, 5), remindersAhead(60, 60 * 60_000L))
@Test fun remindersAfterExtending() = assertEquals(listOf(15, 5), remindersAhead(60, 16 * 60_000L))
@Test fun noRemindersForShortTimer() = assertEquals(emptyList<Int>(), remindersAhead(5, 5 * 60_000L))
@Test fun extensionShortTimer() = assertEquals(10, extensionMin(10))
@Test fun extensionLongTimer() = assertEquals(15, extensionMin(60))
@Test fun roundsUp() = assertEquals(2, minutesLeft(61_000))
```

- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `SleepMath.kt`.**
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `"Sleep timer maths"`.

### Task 4: removed in the plan review

Edit mode (hiding and reordering tiles) was dropped, so there is no bar layout to store. The tile order is `TileId`'s order. The task numbers are kept so the others don't move; Task 9 (screen off) was merged into Tasks 5 and 6 the same way.

### Task 5: Controller: key routing and UI state (pure)

This is the core. The service sends every key here and acts on the returned effects.

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/Controller.kt`
- Test: `app/src/test/kotlin/io/github/lettuceketchup/tvqs/ControllerTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–3.
- Produces:
  - `enum class Key { MENU, UP, DOWN, LEFT, RIGHT, OK, BACK, RED, GREEN, YELLOW, BLUE, FOLLOW_UP, OTHER }`, and `fun keyOf(keyCode: Int): Key`, which maps `android.view.KeyEvent.KEYCODE_*` values, with `DPAD_CENTER` and `ENTER` both mapping to `OK`. Use the keycodes recorded by Task 1's key log. `FOLLOW_UP` is the remote's long-press keys for the colours: 130, 127, 86 and 217 (MENU's 250 stays `OTHER`).
  - `sealed interface Effect` with:
    - `Write(key: String, raw: Int)`. Screen off and on are `Write("power_picture_off", 0)` and `Write("power_picture_off", 1)`; there are no separate effects for them.
    - `Pill(title: String, detail: String, levelPercent: Int?, millis: Long)`, where `millis = 0` means it stays up
    - `HidePill`
    - `SetSleep(minutes: Int)` (0 = cancel), `ExtendSleep(minutes: Int)`
    - `SavePresets(presets: List<Preset>)`
  - `class Controller(tiles: Map<TileId, Tile>, presets: List<Preset>)` with:
    - `fun onKey(key: Key, down: Boolean, heldMs: Long): Boolean`: returns consumed. `heldMs` is `eventTime - downTime` (0 on DOWN). The service calls it for **every** key, `OTHER` included, because rule 0 looks at the previous event. Effects are queued into `val effects: MutableList<Effect>`, which the service drains after each call.
    - `fun refresh(values: Map<TileId, Int>)`: raw setting values (backlight 0–100, mode values as stored). The Preset value is derived with `presetIndex`, not passed in.
    - `var sleepLeftMin: Int` and `var sleepStartedMin: Int`, set by the service before every `onKey` call and every render.
    - `fun showSleepWarning()` and `fun clearSleepWarning()`
    - `fun timeoutClose()`: closes the bar and the pane.
    - `fun screenWentOff()`: the TV was turned off (Task 8's screen-off receiver). Clears `covered` and `warning`, and closes the bar.
    - `val state: UiState`, where `data class UiState(val barOpen: Boolean, val focus: Int, val paneIndex: Int?, val covered: Boolean, val warning: Boolean)`. `focus` indexes `TileId.entries`.
    - `fun valueOf(id: TileId): Int?`, used by the view.
- Routing rules, in priority order:
  0. If the key is `FOLLOW_UP` and the event just before it was a colour UP with `heldMs ≥ 1000`: consume its DOWN and its UP, and do nothing else. Otherwise `FOLLOW_UP` is treated as `OTHER`. This rule comes before rule 1, or a 3 s Blue hold would uncover the screen straight away.
  1. If `covered` (screen off): the next DOWN emits `Write("power_picture_off", 1)`, and both that DOWN and its UP are consumed. (The firmware may also turn the picture back on by itself on a key press; writing 1 again is harmless.)
  2. If `warning`: OK emits `ExtendSleep(extensionMin(sleepStartedMin))` + `HidePill`; BACK emits `HidePill`. Both are consumed, and both clear `warning`.
  3. MENU and colour keys act **on UP**: their DOWN is consumed and nothing happens yet. On UP, `heldMs ≥ 1000` is a long press:
     - MENU long → nothing (the remote sends its own Settings key after about 3 s, which passes through as `OTHER`).
     - Red/Green/Yellow long → save the current backlight and warmth into Night/Evening/Day, then `SavePresets` + `Pill("Saved to Night", "Backlight 37 · Standard", 37, 2000)` (the current values).
     - Blue long → same as short.

     A short press runs the short action: MENU toggles the bar (on close, also closes the pane). Red/Green/Yellow apply the Night/Evening/Day preset: `Write` backlight and warmth, update the values, then `Pill(name, "Backlight X · <warmth label>", X, 2000)`. Blue runs **screen off**: close the bar, set `covered` and emit `Write("power_picture_off", 0)`.
  4. If the bar is closed: return `false` for everything else.
  5. Bar open (consume DOWN and UP for UP/DOWN/LEFT/RIGHT/OK/BACK; let OTHER through), with the pane or normal mode taking the keys as described in the spec's "The bar", "Tile types" and "Sleep timer":
     - Level Up/Down → `stepLevel` then `Write(key, value)`.
     - Mode Up/Down → `stepMode` then `Write`. Preset writes backlight and warmth.
     - Up/Down/Left/Right/OK/Back act on DOWN; their UP is only consumed. A held key steps once (no repeats arrive).
     - Sleep Up → `SetSleep(nextLonger)`; Down → `SetSleep(nextShorter)`.
     - OK on a mode with a pane opens the pane. OK on Screen off runs the same screen-off path as Blue (so `covered` is set).
     - The pane: Up/Down move the highlight (clamped), OK writes and closes the pane, BACK closes the pane.
     - Normal BACK closes the bar.

- [ ] **Step 1: Write the failing tests.** Use a helper `fun Controller.press(k: Key) { onKey(k, true, 0); onKey(k, false, 100) }` and `fun Controller.hold(k: Key, ms: Long = 3000) { onKey(k, true, 0); onKey(k, false, ms) }`. Build it from `defaultTiles()` and `DEFAULT_PRESETS`, and `refresh` backlight 37, warmth 2, picture 0.

```kotlin
@Test fun menuShortOpensBar() { c.press(Key.MENU); assertTrue(c.state.barOpen) }
@Test fun menuHoldDoesNothing() { c.hold(Key.MENU); assertFalse(c.state.barOpen); assertTrue(c.effects.isEmpty()) }
@Test fun menuHoldWhileOpenKeepsBar() { c.press(Key.MENU); c.hold(Key.MENU); assertTrue(c.state.barOpen) }
@Test fun otherKeysPassWhenClosed() = assertFalse(c.onKey(Key.UP, true, 0))
@Test fun leftFromFirstGoesToLast() { c.press(Key.MENU); c.press(Key.LEFT); assertEquals(TileId.SCREEN_OFF, TileId.entries[c.state.focus]) }
@Test fun upOnBacklightWritesNextTick() { c.press(Key.MENU); c.press(Key.UP); assertTrue(Effect.Write("picture_backlight", 40) in c.effects) }
@Test fun presetTileFollowsValues() { c.press(Key.MENU); assertEquals(-1, c.valueOf(TileId.PRESET)); c.press(Key.GREEN); assertEquals(1, c.valueOf(TileId.PRESET)) }
@Test fun okOnPictureOpensPaneButNotOnWarmth() { /* focus PICTURE → OK → paneIndex == current; focus WARMTH → OK → paneIndex == null */ }
@Test fun panePickWritesAndCloses() { /* PICTURE pane, DOWN, OK → Write(picture key, option[1].value), paneIndex null */ }
@Test fun backClosesPaneThenBar() { /* pane open → BACK → bar still open, pane null → BACK → bar closed */ }
@Test fun redAppliesNightWhileBarOpen() { c.press(Key.MENU); c.press(Key.RED); assertEquals(20, c.valueOf(TileId.BACKLIGHT)); assertTrue(c.state.barOpen); assertTrue(c.effects.any { it is Effect.Pill && it.title == "Night" }) }
@Test fun redHoldSavesIntoNightWithoutApplying() { c.hold(Key.RED, 1500); assertTrue(c.effects.any { it is Effect.SavePresets && it.presets[2].backlight == 37 }); assertFalse(c.effects.any { it is Effect.Write }) }
@Test fun greenHeld3sSwallowsFollowUp() { c.hold(Key.GREEN); assertTrue(c.onKey(Key.FOLLOW_UP, true, 0)); assertTrue(c.onKey(Key.FOLLOW_UP, false, 1)) }
@Test fun followUpAlonePasses() = assertFalse(c.onKey(Key.FOLLOW_UP, true, 0))
@Test fun blueHeld3sStaysCovered() { c.hold(Key.BLUE); c.press(Key.FOLLOW_UP); assertTrue(c.state.covered); assertFalse(Effect.Write("power_picture_off", 1) in c.effects) }
@Test fun screenOffTileCovers() { /* MENU, LEFT (focus SCREEN_OFF), OK → covered, Write(power_picture_off, 0), bar closed; next OK DOWN is consumed */ }
@Test fun screenWentOffClearsCover() { c.press(Key.BLUE); c.screenWentOff(); assertFalse(c.state.covered); assertFalse(c.onKey(Key.OK, true, 0)) }
@Test fun blueCoversAndNextKeyIsSwallowed() { c.press(Key.BLUE); assertTrue(c.state.covered); assertTrue(Effect.Write("power_picture_off", 0) in c.effects); assertTrue(c.onKey(Key.OK, true, 0)); assertTrue(c.onKey(Key.OK, false, 100)); assertTrue(Effect.Write("power_picture_off", 1) in c.effects); assertFalse(c.state.covered) }
@Test fun warningOkExtendsByStart() { c.sleepStartedMin = 10; c.showSleepWarning(); c.press(Key.OK); assertTrue(Effect.ExtendSleep(10) in c.effects); assertFalse(c.state.warning) }
@Test fun warningBackOnlyHides() { c.showSleepWarning(); c.press(Key.BACK); assertTrue(Effect.HidePill in c.effects); assertFalse(c.effects.any { it is Effect.ExtendSleep }) }
@Test fun warningBeatsBar() { c.press(Key.MENU); c.showSleepWarning(); c.press(Key.BACK); assertTrue(c.state.barOpen) }
@Test fun sleepUpFromOff() { /* focus SLEEP, sleepLeftMin=0 → UP → SetSleep(10) */ }
@Test fun sleepDownBelowSmallestCancels() { /* sleepLeftMin=8 → DOWN → SetSleep(0) */ }
```

  Write the comment-only tests as real code. Each comment lists the exact keys and assertions.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `Controller.kt`** following the routing rules. Keep the set of DOWN keys it consumed, so each matching UP is consumed too.
- [ ] **Step 4: Run** the tests. Expected: all of Tasks 2, 3 and 5 PASS.
- [ ] **Step 5: Commit** `"Controller: key routing, panes, presets, warning"`.

### Task 5a: Emulator demo harness

A TV-sized Android TV emulator plus one script that installs the debug build, plays a fixed list of remote presses, and records the screen. It lets the user review each UI task without touching the TV. It's built and checked with the Task 1 probe, so it's ready before Task 6 draws anything.

**Found while setting up (2026-11-02):** on this emulator image, neither `adb shell input keyevent` nor `adb emu event send` reaches an accessibility service's `onKeyEvent`. The service was bound with key filtering, and the key log stayed empty. The image is a production build, so there's no root and no `sendevent`. Scripted keys therefore go through a debug broadcast, `DEBUG_KEY`, into the same `handleKey` the real keys use. A demo shows the bar, the pill and the values, but not the key filter itself or keys passing through to other apps; the TV checks cover those. A hold is just a number in the broadcast, so held presses get exact hold times.

**Files:**
- Modify: `QuickService.kt` (`handleKey` and `DEBUG_KEY`)
- Create: `tools/demo.ps1`
- Create: `demo/probe.txt` (this task's steps); Tasks 6–8 add `demo/task6.txt`, `demo/task7.txt` and `demo/task8.txt`
- Modify: `.gitignore` (add `demo/out/`)
- Modify: `README.md` (a short "Try it on the emulator" section)

**Interfaces:**
- Produces: `QuickService.handleKey(keyCode: Int, down: Boolean, heldMs: Long): Boolean`. `onKeyEvent` calls it with `event.eventTime - event.downTime`. For now it only logs and returns `false`; Task 6 fills it in. It logs `key=<code> (<name>) down=<down> held=<ms> consumed=<result>` to `QuickService`, so the demo log shows what was consumed.
- Produces: the debug-only `DEBUG_KEY` broadcast. Its extras are `code` (int) and `hold` (long, default 100), and it calls `handleKey(code, true, 0)` and then `handleKey(code, false, hold)`.
- Produces: `powershell -ExecutionPolicy Bypass -File tools/demo.ps1 demo/<name>.txt` (the execution policy here is Restricted). It writes `demo/out/<name>/`, holding `demo.mp4`, one `<shot>.png` per `shot` step, and `log.txt` (each step, the output of each `shell` step, and `logcat -s QuickService`).

- [x] **Step 1: Emulator and AVD.** Done 2026-11-02 in the terminal tab:
  - Installed with `sdkmanager "emulator" "system-images;android-30;android-tv;x86" "platform-tools"`. That's Android 11 TV, like the Haier. The emulator refuses to start without `platform-tools` inside the SDK; the winget adb on PATH stays the one used.
  - Created the AVD with `avdmanager create avd -n haier_tv -k "system-images;android-30;android-tv;x86" -d tv_1080p`. The "Could not load devices.xml" errors are harmless.
  - Set `hw.keyboard=yes` in `~/.android/avd/haier_tv.avd/config.ini`, so the PC keyboard drives the emulator window.
  - Start it with `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd haier_tv`.
  - Checked: `wm size` is 1920x1080, `wm density` is 320 (the Haier's 960 dp), and the release is 11. `screenrecord` works.
- [ ] **Step 2: Add `handleKey` and `DEBUG_KEY`** to the probe, as in Interfaces. Build with `./gradlew assembleDebug`.
- [ ] **Step 3: Write `tools/demo.ps1`.** Its argument is the steps file.
  1. Every adb call uses `adb -s emulator-5554`. **Never `-e`**: it means "the TCP/IP device", which is the TV when that's connected. Abort unless `shell getprop ro.kernel.qemu` is `1`. After each adb call, stop if `$LASTEXITCODE` isn't 0.
  2. Prepare the emulator:
     - wait until `getprop sys.boot_completed` is 1;
     - install `app/build/outputs/apk/debug/app-debug.apk` with `-r`;
     - run `pm clear io.github.lettuceketchup.tvqs`. Presets saved by an earlier run would change the expected values. A clear also force-stops the app, which drops it from the accessibility services, so the next two come after it;
     - grant `WRITE_SECURE_SETTINGS`;
     - set `enabled_accessibility_services` to `io.github.lettuceketchup.tvqs/.QuickService` and `accessibility_enabled` to 1. The emulator has no Projectivy to keep.
  3. Always `settings put global` these exact values: `picture_backlight 100`, `picture_color_temperature 2`, `picture_mode 7`, `sound_style 1`, `hdmi_system_audio_control_enabled 0`, `power_picture_off 1`. They're fixed so every run starts the same; don't read them from `docs/haier-global-settings.txt`, whose `picture_mode` is 0.
  4. Send `input keyevent WAKEUP` (the previous run may have ended in standby), then `input keyevent HOME`. These are system keys, not ours. Clear logcat, start `shell screenrecord --bit-rate 8000000 /sdcard/demo.mp4` as a background process, and wait 2 s for it to start. screenrecord stops by itself at 180 s, so keep each demo under that.
  5. Run the steps file line by line, skipping blank lines and `#` comments:
     - `key <name> [holdMs]` sends `DEBUG_KEY`; hold defaults to 100. Map names with a small table: MENU 82, BACK 4, DPAD_UP 19, DPAD_DOWN 20, DPAD_LEFT 21, DPAD_RIGHT 22, DPAD_CENTER 23, PROG_RED 183, PROG_GREEN 184, PROG_YELLOW 185, PROG_BLUE 186, MEDIA_PAUSE 127.
     - `wait <seconds>`, which allows decimals.
     - `shot <name>` runs `shell screencap -p /sdcard/shot.png`, then pulls it to `<name>.png`. Don't redirect `exec-out` output: PowerShell 5.1 corrupts binary data in a redirect.
     - `shell <command>` runs `adb shell <command>` and logs the output.
     Each line and its output go to `log.txt`.
  6. Stop the recording (`shell pkill -INT screenrecord`, then wait 2 s), pull `demo.mp4`, append `logcat -d -s QuickService` to `log.txt`, and print the output folder.
- [ ] **Step 4: Write `demo/probe.txt` and run it** (with the emulator running):
  ```
  shot home
  key MENU
  key PROG_RED 1500
  key DPAD_UP
  wait 1
  shot after
  ```
  Expected:
  - `demo/out/probe/` has a `demo.mp4` that plays and two 1920×1080 PNGs. Open the PNGs and look at them.
  - `log.txt` shows MENU, PROG_RED and DPAD_UP, each with a `down=true` and a `down=false` line, `held=1500` on PROG_RED's UP, and `consumed=false` throughout.
  - Also press the arrow keys in the emulator window and check the key log. If they show up, the README can say the window's keyboard reaches the service. If not, it says the keyboard only moves around the launcher.
- [ ] **Step 5: README.** Add a short "Try it on the emulator" section:
  - Start the emulator (the command above), then run the script with `demo/task7.txt` (or the latest demo) to install the build.
  - Drive it in the emulator window with the arrow keys, Enter and Esc, as far as Step 4 found that works.
  - Any key can be sent with `adb -s emulator-5554 shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_KEY --ei code <keycode>`: MENU is 82 and Red/Green/Yellow/Blue are 183–186.
  - From Task 6, backlight, warmth and picture-off are simulated on a grey backdrop (darkening and a tint), not the real panel.
- [ ] **Step 6: Commit** `"Emulator demo harness"`.

### Task 6: Service wiring, settings I/O, screen off and the corner pill

**Files:**
- Modify: `QuickService.kt`
- No `Store.kt`: the only stored data is the presets, so the service keeps them in SharedPreferences `"tvqs"` under `"presets"` (`"Day:100:2;Evening:60:3;Night:20:3"`), with a load and a save function. Bad data falls back to `DEFAULT_PRESETS`.
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/SimView.kt`: the emulator-only picture simulation (Step 1).
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/PillView.kt`: `class PillView(ctx: Context) : LinearLayout` with `fun show(p: Effect.Pill)`. The look is in the spec's "Remote buttons": top-right, about 75% black, rounded 14dp, icon, title, detail and a 4dp level bar.

**Interfaces:**
- Consumes: `Controller`, `Effect`, `keyOf`, `defaultTiles`, `DEFAULT_PRESETS`.
- Produces: `QuickService.overlay(view: View, gravity: Int): WindowManager.LayoutParams`, which Task 7 reuses. It creates `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN` and `PixelFormat.TRANSLUCENT`.

- [ ] **Step 1: Wire up `onKeyEvent`.** Map to `Key` and call `controller.onKey(key, event.action == ACTION_DOWN, event.eventTime - event.downTime)`, then drain and perform the effects. Call it for every key, `OTHER` included (rule 0 needs the previous event). The return value is what's consumed.
  - **Refresh:** before passing any MENU or colour **UP** to `onKey`, read all values with `Settings.Global.getInt(contentResolver, key, default)` and call `refresh`. After a `Write` of `picture_mode`, refresh again about 300 ms later and re-render (the TV keeps backlight, and maybe warmth, per picture mode, and updates the keys itself).
  - `Write` uses `Settings.Global.putInt`; that covers screen off/on too (`power_picture_off`). Wrap each write in `try/catch (SecurityException)`; on failure, show a pill "Permission missing · Grant WRITE_SECURE_SETTINGS over adb" instead of crashing.
  - **Timeout:** a `Handler` calls `timeoutClose()` 8 s after the last key while the bar is open, restarted on each key. It's here and not in Task 7, so a MENU pressed during this task's checks can't leave an invisible bar swallowing the arrows.
  - Leave `SetSleep`/`ExtendSleep` as TODO no-ops for now; Task 8 fills them in. Keep Task 1's key log in debug builds.
  - **Picture simulation (emulator only, user request 2026-11-02).** The emulator has no Haier panel, so in the demos nothing would visibly change. When `BuildConfig.DEBUG && Build.HARDWARE == "ranchu"` (the emulator), `onServiceConnected` adds a `SimView` with `overlay(simView, Gravity.FILL)`, with the params set to `MATCH_PARENT` width and height, **before** the pill and the bar, so it sits under them. `SimView` is a plain `View` with `fun update(backlight: Int, warmth: Int, pictureOff: Boolean)` that stores the values and calls `invalidate()`. Its `onDraw` paints, in order:
    - an opaque light grey (`#C8C8C8`) "picture" (the launcher is too dark to show warmth);
    - the warmth tint: Warm (3) `#FF9A3C` at 18% alpha, Cool (1) `#5AA0FF` at 12%, and nothing for Standard (2) or User (0);
    - black at `(100 - backlight) * 0.8 / 100` alpha, so 100 is clear and 20 is mostly dark;
    - opaque black when `pictureOff`.

    The service calls `simView?.update(...)` with fresh `Settings.Global` reads of `picture_backlight`, `picture_color_temperature` and `power_picture_off` after every drained `Write` and after every `refresh`. On the TV and in release builds `simView` stays null. It's an approximation; say so with each demo.
  - All of this goes in Task 5a's `handleKey(keyCode, down, heldMs)`, which `onKeyEvent` already calls, so `DEBUG_KEY` presses take the same path. Keep its log line with `consumed=`.
- [ ] **Step 2: Build** with `./gradlew assembleDebug testDebugUnitTest`. Expected: success.
- [ ] **Step 2a: Emulator demo.** Write `demo/task6.txt` and run it with `tools/demo.ps1`:
  ```
  shot start
  key PROG_RED
  wait 0.5
  shot night
  shell settings get global picture_backlight
  key PROG_GREEN
  wait 0.5
  shot evening
  shell settings put global picture_backlight 50
  key PROG_GREEN 2000
  wait 0.5
  shot saved-evening
  key PROG_GREEN 4000
  key MEDIA_PAUSE 1
  key PROG_YELLOW
  wait 0.5
  shot day
  key PROG_BLUE
  wait 0.5
  shot picture-off
  shell settings get global power_picture_off
  key DPAD_CENTER
  wait 0.5
  shot picture-back
  shell settings get global power_picture_off
  wait 3
  shot end
  ```
  Expected in `log.txt`: backlight `20` after Red, `0` then `1` around Blue/OK, and both `MEDIA_PAUSE` lines `consumed=true` (rule 0). Expected in the PNGs: the pill top right on `night` ("Night · Backlight 20 · Warm"), `evening`, `saved-evening` ("Saved to Evening · Backlight 50 · Warm") and `day`, and no pill on `start` and `end`. The simulated picture: `start` and `day` bright neutral grey, `night` dark and amber, `evening` mid-dark and amber, `picture-off` black, and `picture-back` the same as before Blue. Look at each PNG against the spec's pill description (position, size at 1080p, legibility), fix what differs, and rerun. Then send the user `demo.mp4` and the PNGs, with the note that the dimming and tint are a simulation. Proceed to the TV install only after the user has seen the demo.
- [ ] **Step 3: Reinstall on the TV (ask the user whether the TV is free first):** `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The grant and the accessibility service from Task 1 stay; check with `adb shell settings get secure enabled_accessibility_services`.
- [ ] **Step 4: Check on the TV**, playing YouTube with sound:
  1. Red, then Yellow. Expected: the screen dims, then brightens; the pill appears top right each time. `adb exec-out screencap -p > shot.png` shows it; look at the screenshot.
  2. Green (backlight 60, Warm), then `adb shell settings put global picture_backlight 50`, then hold Green 2 s. Expected: "Saved to Evening · Backlight 50 · Warm" pill, and the picture doesn't change. Put the default back: `settings put global picture_backlight 60`, hold Green again.
  3. Hold Green 4 s (past the remote's 3 s cap). Expected: the "Saved to Evening" pill, and the video keeps playing (the follow-up `MEDIA_PAUSE` was swallowed).
  4. Long-press MENU. Expected: the same Settings screen as before, and no pill.
  5. Blue. Expected: the picture goes off and the sound continues. While it's off, run `adb shell dumpsys power | grep -i wakefulness`. Record the result: if it isn't `Awake`, Task 8's screen-off receiver must ignore picture-off (see Task 8). Then OK: the picture comes back and the video doesn't pause.
  6. Hold Blue 4 s. Expected: the picture stays off, and Magic Board doesn't open. Then OK brings it back.
  7. On an HDMI input: Red, then MENU. Expected: the pill shows over the HDMI picture, and Haier's own HDMI menu doesn't open (MENU opens nothing yet). If the firmware takes either key first, stop and tell the user.
- [ ] **Step 5: Log** the reinstall and every setting the checks changed in `../tvs/haier/changes.md`. **Commit** `"Service: settings I/O, presets on colour keys, screen off, corner pill"`.

### Task 7: The bar view

**Files:**
- Create: `app/src/main/kotlin/io/github/lettuceketchup/tvqs/BarView.kt`: `class BarView(ctx: Context) : FrameLayout` with `fun render(state: UiState, c: Controller, tiles: Map<TileId, Tile>)`.
- Modify: `QuickService.kt`: add the bar overlay at `Gravity.BOTTOM`, full width, and re-render after every handled key, after a timeout close and after a refresh.

**What the view must show** (everything comes from the spec's "The bar" and "Tile types"):
- Dark translucent strip, about 60% black, rounded 13dp, inset about 3% from the sides and 5% from the bottom. A horizontal row of tiles, with the focused one scrolled into view (centred when possible) and chevrons at the ends when there's more. The screen is 960 dp wide (1920×1080 at 320 dpi), so the 8 tiles don't all fit. Use the platform `HorizontalScrollView`: `smoothScrollTo` the focused tile, and show the chevrons from `canScrollHorizontally(-1/1)`. Every view stays non-focusable and nothing calls `requestFocus`: the Controller owns focus.
- Unfocused tiles: about 12% white, a white hairline border, white text, the icon and name on top, the value at the bottom. Focused tile: solid white with dark text, about 1.3× the width and about 1.15× the height. Size the base width so the longest tile name fits at the TV's font size; the focused tile must never clip.
- Level tiles show the number plus a vertical segmented bar (`100 / BACKLIGHT_STEP` = 10 blocks). When focused, ▲ appears above and ▼ below, each hidden at its limit. Mode and sleep tiles, when focused, show three lines: ▲ previous / current / ▼ next, with the empty end blank. Picture/Sound show "OK ⋯". Action tiles show "Press OK". The list pane sits above the focused tile, with a tick on the current option and the highlight inverted.
- Icons: vector drawables you draw yourself in `res/drawable/` (simple strokes, a few lines of XML each). Don't use Unicode glyphs (the TV font may lack them) and don't add an icon library. If an icon doesn't read on the TV, drop it rather than redraw it.

- [ ] **Step 1: Implement `BarView`** to the rules above.
- [ ] **Step 1a: Emulator demo.** Build, then write `demo/task7.txt` and run it. It covers the same ground as Step 2's items 1–6:
  ```
  shell settings put global picture_backlight 37
  key MENU
  wait 1
  shot open
  key DPAD_UP
  key DPAD_UP
  shell settings get global picture_backlight
  shot backlight-up
  key DPAD_LEFT
  wait 1
  shot wrapped-to-screen-off
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  wait 1
  shot picture-focused
  key DPAD_CENTER
  wait 1
  shot picture-pane
  key DPAD_DOWN
  key DPAD_CENTER
  wait 1
  shot picture-picked
  key MENU
  wait 1
  shot closed
  key MENU
  wait 9
  shot timed-out
  ```
  Expected: `open` shows the bar with Backlight focused at 37; the log shows `50` after the two Ups (37 → 40 → 50, the off-grid snap); `wrapped-to-screen-off` shows Screen off focused with the row scrolled and a left chevron; `picture-pane` shows the list above the Picture tile with a tick on Standard; `picture-picked` shows Vivid; `closed` and `timed-out` show no bar. Check each PNG against "What the view must show" (strip insets, focused tile 1.3× and never clipped, text legible at 1080p), fix what differs, rerun, then send the user the video and PNGs before Step 2.
- [ ] **Step 2: Build and install** (TV free?), and walk through these with the user. Take a screencap after each, check it, and fix anything that differs from the spec before moving on:
  1. MENU opens the bar.
  2. Up/Down on Backlight change the backlight live.
  3. Left from the first tile goes to Screen off (the last), and the row scrolls with the chevrons right.
  4. OK on Picture shows the pane. Pick another mode: the Backlight tile then shows that mode's backlight, not the old value.
  5. MENU closes the bar.
  6. After 8 s idle, the bar closes.
  7. Warmth by eye: User → Cool → Standard → Warm on a white or grey screen. The user confirms each looks different, and that Warm is clearly warmer. (Task 0 only saw the firmware accept the value.)
  8. Output with the ARC audio system on: switch to External audio system and back. The sound moves to the system and back to the TV speakers. If it doesn't, stop and ask the user: the Output tile is dropped (spec: no half-working tiles).
  9. Projectivy: with the Picture pane open, press Back twice quickly. Projectivy also sees both presses (every filtering service gets each key, even one another service consumed), so its Back double press may start the screensaver. Also press MENU twice quickly, and check Projectivy's "Menu double press" setting. If either fires, tell the user. The options are to set that Projectivy action to nothing, or to make one Back close the whole bar.
- [ ] **Step 3: Commit** `"Bar view"`.

### Task 8: Sleep timer runtime and standby

**Files:**
- Modify: `QuickService.kt`

Task 0 result: the TV's sleep timer key (`tv_timer_sleep_timer_entry_values`) only mirrors the menu, and writing it starts nothing. So the app keeps its own countdown and turns the TV off at zero with the standby method Task 1 Step 9 confirmed.

- [ ] **Step 1: Implement the countdown.** Store `endAt = SystemClock.elapsedRealtime() + ms` and `started`. One function, `schedule(msLeft)`, cancels every pending sleep callback and posts each event once with `Handler.postDelayed` (no tick loop):
  - each `m` in `remindersAhead(started, msLeft)`, at `msLeft - m·60000`: `Pill("Sleeping in $m min", "Sleep timer", null, 3000)`;
  - at `msLeft - 60000` (or now, if less is left): `controller.showSleepWarning()` and `Pill("Sleeping in 1 min", "The TV will turn off", null, 0)`, with the OK/Back choices drawn as key caps ("OK +N min", "Back Dismiss"), N = `extensionMin(started)`. It fires once, so a dismissed warning doesn't come back;
  - at `msLeft`: `clearSleepWarning()`, reset, then the standby method.

  `SetSleep(n)` sets `started = n` and calls `schedule(n·60000)`; `SetSleep(0)` cancels everything. `ExtendSleep(n)` moves `endAt` by n minutes, leaves `started` alone and calls `schedule(endAt - now)`. Before every `onKey` call and every render, set `controller.sleepLeftMin = minutesLeft(endAt - now)` (0 when off), so "23 min left" and the Up/Down steps are always current without a tick. While `state.warning` is on, skip any other `Pill`, so the warning can't be replaced by a 2 s pill and then sit there invisibly taking OK and Back.

  `Handler` counts uptime, which stops in deep sleep; that's fine, because the screen holds the TV awake while a timer runs, and the receiver below cancels the timer when the TV goes off.

  **Screen-off receiver:** register for `Intent.ACTION_SCREEN_OFF`. If `PowerManager.isInteractive` is still true (picture-off, if Task 6 Step 4.5 found it sends this broadcast), ignore it. Otherwise cancel the sleep timer and call `controller.screenWentOff()`. This stops a timer the user didn't finish (TV turned off with the remote) from putting the TV back into standby when it's next turned on. Our own standby at zero lands here too, which is harmless.

  The countdown lives in memory: if the service restarts, the timer is gone (*ponytail: in-memory timer; persist `endAt` if restarts turn out to happen*).
  **Debug hook:** add `DEBUG_SLEEP` to the debug-only receiver: `adb shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_SLEEP --ei ms 65000` sets the remaining time by calling `schedule(ms)` (the same path as `SetSleep`). The receiver is only registered when `BuildConfig.DEBUG`, so the release build doesn't have it.
- [ ] **Step 1a: Emulator demo.** Build, then write `demo/task8.txt` and run it:
  ```
  key MENU
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_RIGHT
  key DPAD_UP
  wait 1
  shot sleep-10
  key MENU
  shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_SLEEP --ei ms 65000
  wait 7
  shot warning
  key DPAD_CENTER
  wait 1
  shot extended
  shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_SLEEP --ei ms 65000
  wait 7
  key BACK
  wait 60
  shell dumpsys power | grep -i wakefulness
  ```
  Expected: `sleep-10` shows the Sleep tile at 10 min; `warning` shows "Sleeping in 1 min · The TV will turn off" with the OK/Back key caps; `extended` shows no pill; the last line shows `Asleep` (the emulator went to standby). Check the PNGs, then send the user the video and PNGs before Step 2.
- [ ] **Step 2: Verify on the TV** (TV free?).
  - Set a 10-min sleep. The tile shows the minutes left, and Up shows 15 min.
  - To test without waiting, use Step 1's `DEBUG_SLEEP` broadcast.
  - The last-minute pill appears. OK gives "+10 min" and the countdown jumps. Run it again, press Back: the pill doesn't come back, and the TV goes to standby at zero.
  - Set a 10-min sleep, turn the TV off with the power button, and turn it on again. Expected: it stays on, and the Sleep tile shows Off.
  - Set a 10-min sleep, press Blue (picture off), then `DEBUG_SLEEP` 65000. Expected: the timer still runs (the TV goes to standby at zero), so picture-off doesn't cancel it.
- [ ] **Step 3: Commit** `"Sleep timer runtime and standby"`.

### Task 10: Release build, setup notes, hand-over

**Files:**
- Modify: `README.md`: the "Build environment" becomes "Build and install", with the exact commands from Task 1 and how to undo (turn the service off with `settings put secure enabled_accessibility_services <old value>`, or `adb uninstall io.github.lettuceketchup.tvqs`).
- Modify: `CLAUDE.md`: Next steps.
- Modify: `../DEVICES.md`: fill in any TBD the tests answered (key layout, remote model HTR-U27E).

- [ ] **Step 1: Run** `./gradlew testDebugUnitTest assembleRelease` with a local debug-signed release (`signingConfig = signingConfigs.getByName("debug")`, since the keystore is gitignored). Expected: tests PASS and the APK is built.
- [ ] **Step 2: Install the release APK on the TV** (TV free?), and check the grant and the accessibility service again.
- [ ] **Step 3: Full check with the user.** Have them run through the spec's "Remote buttons" table and every tile once. Then turn USB debugging off, reboot, and confirm that MENU, the colour keys and the sleep timer still work.
- [ ] **Step 4: Log** the final state in `../tvs/haier/changes.md`, and **commit** `"Release build and setup notes"`. Push `main` to `lettuceketchup/tv-quick-settings` once the user approves.
