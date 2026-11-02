# TV Quick Settings

A quick settings overlay for Android TV: press a remote button in any app (YouTube, Netflix, HDMI) and a translucent bar opens at the bottom of the screen with the picture and sound settings used most, so changing backlight in the evening doesn't mean digging through Settings.

Built first for the Haier Android TV in `tv-debloat` (a local project, not on GitHub) (this repo is a submodule there), whose stock quick menu only works on HDMI/Live TV and only offers picture mode.

Status: **design and TV tests done** ([spec](docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md), [TV tests](docs/haier-tests.md)). Nothing built yet.

## What the user described (2026-11-02)

- Press a remote button and a **translucent bottom bar** opens over whatever is playing.
- The bar holds **square tiles**; **Left/Right** moves between them.
- **Backlight** tile with a **level bar** under it; **Up/Down** changes the level live.
- **Night mode / blue light** tile, adjusted the same way.
- **User presets** tile (e.g. day / evening / night combinations).
- **Picture mode** tile (may just open the TV's own picture mode menu).
- **Sound mode and sound settings** tile.
- Add other relevant settings; look at what other brands do (Samsung, LG, Sony, TCL, Hisense quick menus) for ideas.
- Why: room light changes in the evening and at night, and backlight is the setting needed most. The Hisense TV has a quick menu like this; the Haier doesn't outside Live TV.

## What's already known

- The Haier (MediaTek m7322, Android 11) stores its picture and sound settings in `Settings.Global`: `picture_backlight`, `picture_blue_light`, `picture_mode`, `sound_style`, the equalizer and more. Full list with current values: [docs/haier-global-settings.txt](docs/haier-global-settings.txt).
- **Writing `picture_backlight` takes effect immediately** (tested 2026-11-02: 100 → 30 dimmed the screen, restored to 100). The other keys are untested; test each before relying on it.
- Writing `Settings.Global` needs `android.permission.WRITE_SECURE_SETTINGS`, granted once over adb with `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`. The grant survives reboots and turning USB debugging off; it is lost only if the app is uninstalled. **The app must not depend on adb at runtime**, because the user turns USB debugging off when done.
- Haier's own quick menu lives in `com.mediatek.wwtv.tvcenter` and only appears on HDMI/Live TV.

## Open questions for the design

- How the remote button opens the overlay from inside any app: an accessibility service listening for a key (like Button Mapper/tvQuickActions do), or a remapper app launching ours.
- Which button to use (ideally a long press of one already on the remote).
- Overlay drawing: an accessibility overlay window vs `SYSTEM_ALERT_WINDOW`; focus and d-pad handling over video.
- Which keys actually apply live (blue light, picture mode, sound mode, equalizer).
- Whether to support the Hisense too (different firmware; its keys are unknown).
- Possible later feature: automatic backlight by time of day.

## Build environment

Set up 2026-11-02: JDK 17 and the Android command-line tools on this Windows PC (no Android Studio), Kotlin, no runtime libraries. `./gradlew assembleDebug` builds the APK and `./gradlew testDebugUnitTest` runs the unit tests. Install on the TV with `adb install -r`, then the `pm grant` above (once).

## Try it on the emulator

The `haier_tv` emulator (Android 11 TV, 1920×1080, like the Haier's UI) runs the real debug build without touching the TV.

1. Start it from a terminal: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd haier_tv`
2. Build, then run a demo, which also installs the build: `./gradlew assembleDebug`, then `powershell -ExecutionPolicy Bypass -File tools/demo.ps1 demo/task7.txt` (or the latest demo). The video, screenshots and log land in `demo/out/<name>/`.
3. Drive it in the emulator window: the arrow keys, Enter (OK) and Esc (Back) reach the app like remote keys (checked 2026-11-03).
4. For MENU and the colour buttons, which the PC keyboard doesn't have, send the key with `adb -s emulator-5554 shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_KEY --es key <name>`, e.g. `MENU`, `DPAD_UP` or `PROG_RED` to `PROG_BLUE`. Add `--el hold 1500` for a held press. Always use `-s emulator-5554`, never `-e`: that's the TV when it's connected.

Keys typed with `adb shell input keyevent` don't reach the app on the emulator, which is why the broadcast exists. The emulator has no Haier panel: from Task 6, backlight, warmth and screen off are simulated on a grey backdrop (darkening and a tint).
