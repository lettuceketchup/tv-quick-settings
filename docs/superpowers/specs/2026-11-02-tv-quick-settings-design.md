# TV Quick Settings: design

Date: 2026-11-02. Status: design and look agreed with the user (mocks done). Next: implementation plan.

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

The remote is a Haier HTR-U27E. The key codes below are the standard Android ones and still need to be confirmed on the TV.

| Button | Action |
|---|---|
| MENU, short press | Open the bar; if it's open, close it. Everywhere, including HDMI/Live TV (replaces Haier's HDMI menu, which the user doesn't need). |
| MENU, long press | Open Settings, the same screen the long press opens today. The app opens it itself, because once the press is caught it can't be passed back to the TV. Find the exact screen on the TV. |
| Red | Night preset |
| Green | Evening preset |
| Yellow | Day preset |
| Long press of Red/Green/Yellow | Save the current backlight + blue light into that preset (like a car radio's preset buttons). *My call; the mocks may change it.* |
| Blue | Screen off (the sound keeps playing). Any button turns the picture back on; that press is swallowed so it doesn't also pause or skip the video (*my call*). |
| Everything else | Unchanged. |

MENU and the colour buttons are taken over in every app with no exceptions. The user confirmed they don't use them anywhere else (including Projectivy, Jellyfin and Live TV teletext), so there's no per-app exception list.

If the key log shows the firmware takes MENU before the app can see it, FAV opens the bar instead (*my call*; FAV and INFO were the spare buttons discussed). If a colour button can't be caught, its preset is only reachable from the bar.

Every button action that changes a setting shows the **corner pill**: a small translucent box at the top right with an icon, a title, one line of detail and a thin level bar, e.g. "Night / Backlight 20 · Blue light 75". It fades after about 2 seconds and doesn't take focus.

## The bar

Look and behaviour were settled with the user through interactive mocks (the last one is "v10" in the 2026-11-02 chat).

- Translucent dark strip along the bottom of the screen, with a single **horizontally scrolling row of tiles**. About seven tiles fit on screen. The row slides to keep the focused tile in view, and chevrons at the edges show there's more.
- **Every tile:** icon and name on top, value at the bottom. Unfocused tiles are translucent with white text.
- **The focused tile** is solid white with dark text, only a little larger than the others (about 1.3× wider and slightly taller). The user rejected a big jump in size. Names must not be clipped, so use a width that fits the longest name.
- **Left/Right** moves focus and **wraps around**: Left on the first tile goes to the last, and Right on the last goes to the first.
- **Up/Down** changes the focused tile's value and applies it at once. The tile is laid out so the direction of the press is obvious (below).
- **Back** or **MENU** closes the bar. It also closes after about 8 seconds without a press, except in edit mode (*my call*).
- The video keeps playing underneath.

### Tile types

- **Level** (Backlight, Blue light): the value as a number, with a short **vertical segmented bar** beside it. Each segment is one step. The focused tile shows ▲ above the bar and ▼ below it, and hides an arrow at its limit.
  - Backlight: steps of 10, so 10 segments (0–100).
  - Blue light: steps of a quarter, so 4 segments (off, 25, 50, 75, 100 of the key's range).
- **Mode** (Preset, Picture, Sound, Dialogue, Output): the focused tile shows three lines, "▲ previous option", the **current option**, then "▼ next option". Modes **don't wrap**: at either end that line is blank, so a two-option mode like Dialogue never shows the same option on both sides.
  - Picture, Sound and Output have an **"OK ⋯"** hint. OK opens a **list pane** above the tile with every option and a tick on the current one. Up/Down moves the highlight, OK picks and Back closes the pane.
  - Preset and Dialogue have no pane, because Up/Down covers their few options.
- **Sleep**: see "Sleep timer" below. No pane.
- **Action** (Ambient, Screen off): the focused tile says "Press OK".
- **Edit**: always the last tile, and it can't be hidden. See "Editing the bar".

### Tiles (default order)

1. **Backlight**
2. **Blue light**
3. **Preset**: Day / Evening / Night, applied as you move.
4. **Picture**: the TV's own picture modes, with the TV's names. The app only switches between modes and never edits what's inside one. Tuning a mode is done in the TV's Settings. If the key doesn't apply live, the tile opens the TV's own picture mode menu instead.
5. **Sound**: the TV's own sound modes, same rule as Picture.
6. **Dialogue**: Off / On.
7. **Output**: speakers / Bluetooth / ARC, or opens the TV's sound output screen if it can't be switched directly.
8. **Sleep**
9. **Ambient**: OK starts the TV's own screensaver now (whatever is set in Android's screensaver settings), and any button returns. Drop this tile if the TV already has a button or combination that does the same; check during the key tests.
10. **Screen off**: OK turns the picture off and the sound keeps playing. Any button turns it back on.
11. **Edit**

### Editing the bar

- OK on the Edit tile enters edit mode, and a small label "Editing the bar · Back done" appears above the bar. The row then shows **every** tile, including hidden ones.
- Hidden tiles are **dimmed**: faded, with a dashed border and an eye-off mark. A focused hidden tile stays greyed out instead of turning bright white. Each tile says Shown or Hidden.
- **Up/Down** toggles shown/hidden.
- **OK** picks the tile up (it lifts slightly and says "Moving"). **Left/Right** moves it, and it stops at the ends rather than wrapping (*my call*). **OK** drops it.
- **Back** leaves edit mode.
- The order and the hidden tiles are saved in the app's storage.

## Sleep timer

- Steps: 10, 15, 30, 60, 90, 120 min. **Up = longer, Down = shorter.**
- **When off**, the tile shows "Off" with "▲ 10 min".
- **Once running**, the main text is the **actual time left** ("23 min left"), and the ▲/▼ lines show the next step above and below the time left (e.g. 30 min / 15 min). Pressing one restarts the timer at that value. Down from below the smallest step shows **Off**, which cancels the timer. Unfocused, the tile shows only the time left.
- **When it reaches zero**, the TV goes to standby, the same as pressing power, and it wakes the usual way. If the Haier has its own sleep-timer setting, use it; otherwise the app keeps the countdown and needs a way to put the TV in standby (find it during the tests).
- **Reminders:** a short corner pill (about 3 s), "Sleeping in 30 min", when the time left passes 6 h, 2 h, 1 h, 30, 15 and 5 min. Only the steps shorter than the timer's length fire.
- **Last minute:** the pill stays up and says "Sleeping in 1 min · The TV will turn off" with two clearly shown choices:
  - **OK** extends the timer by 15 min, or by the timer's original length if that was shorter, so a 10‑min timer offers "+10 min".
  - **Back** hides the pill, and the TV still turns off.
  - While it's showing, OK and Back only answer the pill.

## Presets

- Three presets: Day, Evening, Night. Each holds **backlight and blue light only**. Picture mode and sound are never touched by a preset.
- Starting values on the tick grid (*my call*, adjust after testing): Day = backlight 100, blue light off; Evening = 60, 50; Night = 20, 75.
- Stored in the app's own storage, so they survive reboots.
- A preset writes the keys once, to whatever input and picture mode are current. If the TV turns out to keep a separate backlight per input or per picture mode, pressing the preset again after switching applies it there too. The app doesn't try to write every input at once (*my call*; revisit if it feels broken).

## How it works

- **One app** with an **accessibility service** that asks to filter key events. It sees each remote press before the foreground app does, so it can catch MENU and the colour buttons and let everything else through.
- While the bar is open, the service also catches the arrows, OK and Back and drives the bar itself. The overlay window never takes input focus, so the video player isn't disturbed. *My call*; it avoids focus problems over video.
- The bar and the popup are drawn as an **accessibility overlay window**, so no "draw over other apps" permission is needed.
- Settings are changed by writing `Settings.Global` keys (see `docs/haier-global-settings.txt`). That needs `WRITE_SECURE_SETTINGS`, which is granted once over adb and survives reboots and USB debugging being off.
- The app reads the current values when the bar opens, so it stays in sync with changes made through the TV's own menus.

### Setting keys to confirm on the TV

Only `picture_backlight` has been tested (writing it dims the screen live). Every other tile depends on a test first: write the value, ask the user what changed, then restore the old value. Run the tests only when nobody is watching.

| Tile | Candidate key | What to learn |
|---|---|---|
| Backlight | `picture_backlight` (0–100) | Tested. Also check whether HDMI and apps keep separate values, and whether the value is per picture mode. |
| Blue light | `picture_blue_light` | Range, and whether it applies live. |
| Picture mode | `picture_mode` | Value → name mapping (change it in the TV menu and read the key), and whether writing it applies live. |
| Sound mode | `sound_style` | Value → name mapping; live? |
| Dialogue boost | `sound_dialog_enhancement` | Live? |
| Sound output | `sound_digital_output`? unknown | It may not be switchable by key; fallback is to open the TV's screen. |
| Sleep timer | unknown (the dump only listed picture/sound keys) | Look for a Haier sleep-timer key or intent. Fallback: the app counts down itself and puts the TV to sleep (method to find), or opens the TV's sleep timer screen. |
| Ambient | none: start the screensaver (e.g. SystemUI's `Somnambulator` activity) | Whether it starts on the Haier, and whether a remote button or combination already does this; drop the tile if so. |
| Screen off | `power_picture_off`? | Whether it turns the panel off with sound playing. Fallback (*my call*): a full black overlay plus backlight 0, both undone on the next press. |

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
- The MENU long-press Settings screen has to be found and opened by intent.

## Left to the builder

Package name, minimum/target SDK (the TV runs Android 11), the build setup on Windows with the command-line tools, the step sizes, the animation timing, and the code structure.

## Next steps

1. Implementation plan. Its first phase is the key tests on the TV (when it's free): the button log and the setting keys table above. The tile list is settled from those results before any tile is built. Install the JDK and the Android command-line tools, then build.
