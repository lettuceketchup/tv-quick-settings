# TV Quick Settings: design

Date: 2026-11-02. Status: design and look agreed with the user (mocks done). TV tests done (Task 0, see `docs/haier-tests.md`): Blue light became Warmth; Dialogue and Ambient were dropped. Plan review (2026-11-02, `docs/superpowers/reviews/`): edit mode was dropped, so the tile order is fixed in code.

## Why

On the Haier Android TV the room light changes in the evening and at night, and the backlight is the setting the user changes most. Haier's quick menu only works on HDMI/Live TV and only offers picture mode. Elsewhere (YouTube, Netflix, Jellyfin, Projectivy) the user has to dig through Settings. The Hisense has a good quick menu; the Haier should get one too.

Success: from any app, one press of MENU opens a bar, Up/Down dims the screen live, and the video keeps playing. One press of a colour button jumps straight to Day, Evening or Night.

## Goals

- A translucent bar at the bottom of the screen, over whatever is playing, in every app and on HDMI.
- One-press presets on the colour buttons, with a short on-screen confirmation.
- Works with USB debugging turned off. adb is only used once, for setup.
- Simple: one small app, Kotlin, no third-party libraries.

## Non-goals

- The Hisense. Haier only for now (the Hisense already has a quick menu).
- Automatic backlight by time of day. Possible later; the presets cover it for now.
- Picture size / aspect ratio, volume leveller, bass/treble/equaliser. These stay in the TV's own Settings.
- A phone app, a settings screen inside the app, or a launcher icon beyond what Android requires.

## Anti-goals (a failure even if it works)

- Breaking a remote button the user relies on. Only MENU and the four colour buttons change; everything else, including MENU long press, behaves as before.
- Anything irreversible on the TV. Follow the parent project's rules: only reversible changes, log setting changes in `../tvs/haier/changes.md`, never operate the TV while someone is watching.
- Interrupting playback: opening the bar must not pause or steal focus from the video.
- Needing adb, root or a helper app after setup.

## Remote buttons

The remote is a Haier HTR-U27E (Bluetooth). Its raw codes are in `docs/haier-tests.md`; the Android keycodes of MENU, the colour buttons and FAV are confirmed by the app's own key log in the first on-TV build.

| Button | Action |
|---|---|
| MENU (≡), short press | Open the bar; if it's open, close it. Everywhere, including HDMI/Live TV (replaces Haier's HDMI menu, which the user doesn't need). Act on release, and only if it was held less than 1 s, because a long press holds this key down for about 3 s first. |
| MENU, long press | Unchanged. After about 3 s the remote itself sends a different key, which opens Settings (`com.android.tv.settings/.MainSettings`). The app lets that key through and does nothing on a long MENU hold. |
| Red | Night preset |
| Green | Evening preset |
| Yellow | Day preset |
| Long press of Red/Green/Yellow | Save the current backlight + warmth into that preset (like a car radio's preset buttons). It happens on release, after a hold of 1 s or more, because the app gets no key repeats and can only measure how long the key was down. *My call; the mocks may change it.* A hold of 3 s or more makes the remote send its own follow-up key (Red 130, Green 127, Yellow 86, Blue 217); the app swallows it, so a held Green doesn't also pause the video and a held Blue doesn't open Magic Board. |
| Blue | Screen off (the sound keeps playing). Any button turns the picture back on; that press is swallowed so it doesn't also pause or skip the video (*my call*). |
| Everything else | Unchanged. |

MENU and the colour buttons are taken over in every app with no exceptions. The user confirmed they don't use them anywhere else (including Projectivy, Jellyfin and Live TV teletext), so there's no per-app exception list.

If the key log shows the firmware takes MENU before the app can see it, FAV opens the bar instead (*my call*; FAV and INFO were the spare buttons discussed). If a colour button can't be caught, its preset is only reachable from the bar.

Every button action that changes a setting shows the **corner pill**: a small translucent box at the top right with an icon, a title, one line of detail and a thin level bar, e.g. "Night / Backlight 20 · Warm". It fades after about 2 seconds and doesn't take focus.

## The bar

Look and behaviour were settled with the user through interactive mocks (the last one is "v10" in the 2026-11-02 chat).

- Translucent dark strip along the bottom of the screen, with a single **horizontally scrolling row of tiles**. About seven tiles fit on screen. The row slides to keep the focused tile in view, and chevrons at the edges show there's more.
- **Every tile:** icon and name on top, value at the bottom. Unfocused tiles are translucent with white text.
- **The focused tile** is solid white with dark text, only a little larger than the others (about 1.3× wider and slightly taller). The user rejected a big jump in size. Names must not be clipped, so use a width that fits the longest name.
- **Left/Right** moves focus and **wraps around**: Left on the first tile goes to the last, and Right on the last goes to the first.
- **Up/Down** changes the focused tile's value and applies it at once, one step per press (holding the key doesn't keep stepping). The tile is laid out so the direction of the press is obvious (below).
- **Back** or **MENU** closes the bar. It also closes after about 8 seconds without a press (*my call*).
- The video keeps playing underneath.

### Tile types

- **Level** (Backlight): the value as a number, with a short **vertical segmented bar** beside it. Each segment is one step. The focused tile shows ▲ above the bar and ▼ below it, and hides an arrow at its limit.
  - Backlight: steps of 10, so 10 segments (0–100).
- **Mode** (Warmth, Preset, Picture, Sound, Output): the focused tile shows three lines, "▲ previous option", the **current option**, then "▼ next option". Modes **don't wrap**: at either end that line is blank, so a two-option mode like Output never shows the same option on both sides.
  - Picture and Sound have an **"OK ⋯"** hint. OK opens a **list pane** above the tile with every option and a tick on the current one. Up/Down moves the highlight, OK picks and Back closes the pane.
  - Warmth, Preset and Output have no pane, because Up/Down covers their few options.
- **Sleep**: see "Sleep timer" below. No pane.
- **Action** (Screen off): the focused tile says "Press OK". OK does the same as Blue.

### Tiles (in order)

1. **Backlight**
2. **Warmth**: the TV's own Colour Temperature, User / Cool / Standard / Warm (`picture_color_temperature` 0 / 1 / 2 / 3). This is the night mode: the TV has no working blue-light key, and its "Warm" is what other brands sell as a low-blue mode. Research says total brightness matters more than colour for sleep, so Backlight stays first.
3. **Preset**: Day / Evening / Night, applied as you move. The tile shows the preset whose backlight and warmth both match the TV's current values, or "Custom" when none does.
4. **Picture**: the TV's own picture modes, with the TV's names. The app only switches between modes and never edits what's inside one. Tuning a mode is done in the TV's Settings.
5. **Sound**: the TV's own sound modes, same rule as Picture.
6. **Output**: TV Speakers / External audio system (HDMI ARC).
7. **Sleep**
8. **Screen off**: OK turns the picture off and the sound keeps playing. Any button turns it back on.

Dropped after the TV tests: **Blue light** (the key does nothing visible), **Dialogue** (no such option on this TV), and **Ambient** (Projectivy already starts the screensaver on a Back double press).

Dropped in the plan review: **Edit** (hiding and reordering tiles). The order above is a constant in the code; change it there. It can come back later as its own task.

## Sleep timer

- Steps: 10, 15, 30, 60, 90, 120 min. **Up = longer, Down = shorter.**
- **When off**, the tile shows "Off" with "▲ 10 min".
- **Once running**, the main text is the **actual time left** ("23 min left"), and the ▲/▼ lines show the next step above and below the time left (e.g. 30 min / 15 min). Pressing one restarts the timer at that value. Down from below the smallest step shows **Off**, which cancels the timer. Unfocused, the tile shows only the time left.
- **When it reaches zero**, the TV goes to standby, the same as pressing power, and it wakes the usual way. The Haier's own sleep timer can't be started by writing its key, so the app keeps the countdown and puts the TV in standby itself (method checked in the build).
- Times are always shown in minutes ("90 min left").
- **Turning the TV off another way** (the power button) cancels the timer, so it can't put the TV back into standby when it's next turned on. Screen off doesn't cancel it.
- **Reminders:** a short corner pill (about 3 s), "Sleeping in 30 min", when the time left passes 60, 30, 15 and 5 min. Only the steps shorter than the timer's length fire.
- **Last minute:** the pill stays up and says "Sleeping in 1 min · The TV will turn off" with two clearly shown choices:
  - **OK** extends the timer by 15 min, or by the timer's original length if that was shorter, so a 10‑min timer offers "+10 min".
  - **Back** hides the pill, and the TV still turns off.
  - While it's showing, OK and Back only answer the pill.

## Presets

- Three presets: Day, Evening, Night. Each holds **backlight and warmth only**. Picture mode and sound are never touched by a preset.
- Starting values (*my call*, adjust after testing): Day = backlight 100, Standard; Evening = 60, Warm; Night = 20, Warm.
- Stored in the app's own storage, so they survive reboots.
- A preset writes the keys once, to whatever input and picture mode are current. The TV keeps a separate backlight per input and per picture mode (tested), so pressing the preset again after switching applies it there too. The app doesn't try to write every input at once (*my call*; revisit if it feels broken).

## How it works

- **One app** with an **accessibility service** that asks to filter key events. It sees each remote press before the foreground app does, so it can catch MENU and the colour buttons and let everything else through.
- While the bar is open, the service also catches the arrows, OK and Back and drives the bar itself. The overlay window never takes input focus, so the video player isn't disturbed. *My call*; it avoids focus problems over video.
- The bar and the popup are drawn as an **accessibility overlay window**, so no "draw over other apps" permission is needed.
- Settings are changed by writing `Settings.Global` keys (see `docs/haier-global-settings.txt`). That needs `WRITE_SECURE_SETTINGS`, which is granted once over adb and survives reboots and USB debugging being off.
- The app reads the current values when the bar opens or a colour button is pressed, so it stays in sync with changes made through the TV's own menus. It reads them again after switching Picture mode, because the TV keeps a backlight (and maybe warmth) per picture mode.

### Setting keys to confirm on the TV

Tested on 2026-11-02 (Task 0). Details, raw codes and logs: `docs/haier-tests.md`. Every write was restored and logged in `../tvs/haier/changes.md`.

| Tile | Candidate key | What to learn | Result |
|---|---|---|---|
| Backlight | `picture_backlight` (0–100) | Tested. Also check whether HDMI and apps keep separate values, and whether the value is per picture mode. | **Keep.** Live. Separate per input and per picture mode; the key reads and writes the current one. |
| Blue light | `picture_blue_light` | Range, and whether it applies live. | **Dropped.** Stored, but no visible change. Replaced by **Warmth**: `picture_color_temperature` 0 User, 1 Cool, 2 Standard, 3 Warm (applied by the firmware). |
| Picture mode | `picture_mode` | Value → name mapping (change it in the TV menu and read the key), and whether writing it applies live. | **Keep.** Live. 0 User, 2 Sport, 3 Vivid, 7 Standard, 9 Movie, 10 Game, 11 Energy Saving; list in menu order (User, Standard, Vivid, Sport, Movie, Game, Energy Saving). **Write only these values**: 12/13 corrupted the User mode. |
| Sound mode | `sound_style` | Value → name mapping; live? | **Keep.** Live. 0 User, 1 Standard, 2 Night, 3 Sports, 4 Movie, 5 Music, 6 News. |
| Dialogue boost | `sound_dialog_enhancement` | Live? | **Dropped.** No such option in the TV's menus. |
| Sound output | `sound_digital_output`? unknown | It may not be switchable by key; fallback is to open the TV's screen. | **Keep.** `hdmi_system_audio_control_enabled`: 0 TV Speakers, 1 External audio system (ARC). |
| Sleep timer | unknown (the dump only listed picture/sound keys) | Look for a Haier sleep-timer key or intent. Fallback: the app counts down itself and puts the TV to sleep (method to find), or opens the TV's sleep timer screen. | **Fallback: the app counts down** and puts the TV in standby. `tv_timer_sleep_timer_entry_values` only mirrors the menu; writing it starts nothing. |
| Ambient | none: start the screensaver (e.g. SystemUI's `Somnambulator` activity) | Whether it starts on the Haier, and whether a remote button or combination already does this; drop the tile if so. | **Dropped.** Projectivy's Back double press already starts `Somnambulator`. (It shows nothing until a screensaver is enabled again: Backdrop was disabled by the debloat.) |
| Screen off | `power_picture_off`? | Whether it turns the panel off with sound playing. Fallback (*my call*): a full black overlay plus backlight 0, both undone on the next press. | **Keep.** `power_picture_off` 0 = picture off, 1 = on. Tested live. |

A tile whose setting can't be changed live, and has no working fallback, is dropped rather than half-working.

## Setup (one time, over adb)

1. `adb install` the app.
2. `pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
3. Turn the accessibility service on. Android TV may not list third-party services in its menu, so this is done with `settings put secure enabled_accessibility_services ...` and `accessibility_enabled 1`.
4. Log the steps in `../tvs/haier/changes.md`. USB debugging can then be turned off.

To undo: turn the service off, or uninstall the app. Settings the app changed stay as the last value written, and the TV's own menus can change them back.

## Risks

- **Firmware may handle some buttons before the app sees them.** Confirm with a read-only key log (`getevent`/logcat) while the user presses MENU and the colour buttons.
- **Accessibility services can be switched off** by the system after updates or crashes. If that happens, re-enable over adb. A self-check inside the app is not planned.
- The colour buttons all arrive as the same Linux key (`KEY_UNKNOWN`) and differ only by HID usage. If Android doesn't map them to different keycodes, the presets are only reachable from the bar.
- Projectivy's accessibility service also filters keys. Both services see each press, even one the other consumes. So two quick Backs that close the pane and then the bar can also trigger Projectivy's Back double press (screensaver), and two quick MENU presses its "Menu double press". Checked on the TV in the bar build; the fix is to clear that Projectivy action or let one Back close the whole bar.
- **No key repeats reach the app** (most likely: accessibility services see keys before Android makes repeats). Long presses are measured from how long the key was held, and holding Up/Down steps once. The first build logs this to confirm.
- **Picture off may look like "screen off" to Android.** If `power_picture_off 0` makes Android report the screen as off, the "TV turned off" check must ignore it, or Screen off would cancel the sleep timer. Checked on the TV in the service build.
- **HDMI/Live TV may take MENU or the colour keys** (teletext) before the app sees them. The key log only ran in YouTube; checked on HDMI in the service build.
- **Standby at the end of the sleep timer** relies on the accessibility "lock screen" action putting the TV to sleep. The first build checks it before the timer is built.

## Left to the builder

Package name, minimum/target SDK (the TV runs Android 11), the build setup on Windows with the command-line tools, the step sizes, the animation timing, and the code structure.

## Next steps

1. Implementation plan. Its first phase is the key tests on the TV (when it's free): the button log and the setting keys table above. The tile list is settled from those results before any tile is built. Install the JDK and the Android command-line tools, then build.
