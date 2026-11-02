# TV Quick Settings: design

Date: 2026-11-02. Status: design agreed in chat. Visual mocks come next and may change the look and tile order, but not the scope.

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

Every button action that changes a setting shows a **small temporary overlay** with the new state, for example "Night · Backlight 25 · Blue light 60" with level bars. It fades after about 2 seconds and doesn't take focus.

## The bar

- Translucent strip along the bottom of the screen with a row of square tiles. Each tile shows a name, the current value and, for levels, a bar under it.
- Left/Right moves between tiles. Up/Down changes the selected tile's value and **applies it at once**. Back or MENU closes the bar. It also closes after about 8 seconds without a press (*my call*).
- The video keeps playing underneath.

Tiles (the order will be settled in the mocks):

1. **Backlight**: level, Up/Down in steps (step size *builder's call*, e.g. 5).
2. **Blue light**: level or steps, whichever the key supports.
3. **Presets**: Up/Down picks Day/Evening/Night and applies it.
4. **Picture mode**: Up/Down cycles the modes by name. If the key doesn't apply live, the tile opens the TV's own picture mode menu instead.
5. **Sound mode**: Up/Down cycles the modes by name.
6. **Dialogue boost**: on/off.
7. **Sound output**: speakers / Bluetooth / ARC, or opens the TV's sound output screen if it can't be switched directly.
8. **Sleep timer**: off / 15 / 30 / 60 / 90 / 120 minutes.
9. **Screen off**: OK turns the picture off.

## Presets

- Three presets: Day, Evening, Night. Each holds **backlight and blue light only**. Picture mode and sound are never touched by a preset.
- Starting values (*my call*, adjust after testing): Day = backlight 100, blue light off; Evening = 60, medium; Night = 25, high.
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

1. Visual mocks of the bar and the popup for the user to choose from and modify.
2. Implementation plan. Its first phase is the key tests on the TV (when it's free): the button log and the setting keys table above. The tile list is settled from those results before any tile is built. Install the JDK and the Android command-line tools, then build.
