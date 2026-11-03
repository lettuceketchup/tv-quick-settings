# TV Quick Settings

A quick settings overlay for Android TV: press a remote button in any app (YouTube, Netflix, HDMI) and a translucent bar opens at the bottom of the screen with the picture and sound settings used most, so changing backlight in the evening doesn't mean digging through Settings.

Built first for the Haier Android TV in `tv-debloat` (a local project, not on GitHub) (this repo is a submodule there), whose stock quick menu only works on HDMI/Live TV and only offers picture mode.

Status: **built and in use on the Haier** (release build, 2026-11-03). [Spec](docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md), [plan](docs/superpowers/plans/2026-11-02-tv-quick-settings.md), [TV tests](docs/haier-tests.md).

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

## Build and install

Set up 2026-11-02: JDK 17 and the Android command-line tools on this Windows PC (no Android Studio), Kotlin, no runtime libraries.

Build (the release is signed with the debug key, fine for sideloading; `*.keystore` is gitignored):

```
./gradlew testDebugUnitTest assembleRelease
```

Install on the Haier (USB debugging on, `adb connect 192.168.29.186:5555`):

```
adb -s 192.168.29.186:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s 192.168.29.186:5555 shell pm grant io.github.lettuceketchup.tvqs android.permission.WRITE_SECURE_SETTINGS
adb -s 192.168.29.186:5555 shell settings get secure enabled_accessibility_services
adb -s 192.168.29.186:5555 shell settings put secure enabled_accessibility_services <old value>:io.github.lettuceketchup.tvqs/.QuickService
adb -s 192.168.29.186:5555 shell settings put secure accessibility_enabled 1
```

The grant and the service survive reinstalls (`install -r` with the same key), reboots and turning USB debugging off, so only the first install needs the last four lines. The old value on the Haier was Projectivy's service alone, `com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService`. Since 2026-11-03 Projectivy's service is off and the value is the app's service alone: any other service that filters keys breaks held volume (see `../tvs/haier/reference.md`). After a reinstall the service rebinds within a few seconds; if it doesn't, set the old value and then the new one again.

Undo: turn the service off with `settings put secure enabled_accessibility_services <old value>`, or remove the app with `adb uninstall io.github.lettuceketchup.tvqs` (that also drops the grant).

The debug build (`./gradlew assembleDebug`, `app/build/outputs/apk/debug/app-debug.apk`) adds adb hooks for testing: `am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_KEY --es key <NAME>`, `DEBUG_SLEEP --ei ms <ms>` and `DEBUG_STANDBY`. The release build has none of them.

## Try it on the emulator

The `haier_tv` emulator (Android 11 TV, 1920×1080, like the Haier's UI) runs the real debug build without touching the TV.

1. Start it from a terminal: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd haier_tv`
2. Build, then run a demo, which also installs the build: `./gradlew assembleDebug`, then `powershell -ExecutionPolicy Bypass -File tools/demo.ps1 demo/task7.txt` (or the latest demo). The video, screenshots and log land in `demo/out/<name>/`.
3. Drive it in the emulator window: the arrow keys, Enter (OK) and Esc (Back) reach the app like remote keys (checked 2026-11-03).
4. For MENU and the colour buttons, which the PC keyboard doesn't have, send the key with `adb -s emulator-5554 shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_KEY --es key <name>`, e.g. `MENU`, `DPAD_UP` or `PROG_RED` to `PROG_BLUE`. Add `--el hold 1500` for a held press. Always use `-s emulator-5554`, never `-e`: that's the TV when it's connected.

Keys typed with `adb shell input keyevent` don't reach the app on the emulator, which is why the broadcast exists. The emulator has no Haier panel: from Task 6, backlight, warmth and screen off are simulated on a grey backdrop (darkening and a tint).
