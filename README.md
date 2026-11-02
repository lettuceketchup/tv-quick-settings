# TV Quick Settings

A quick settings overlay for Android TV: press a remote button in any app (YouTube, Netflix, HDMI) and a translucent bar opens at the bottom of the screen with the picture and sound settings used most, so changing backlight in the evening doesn't mean digging through Settings.

Built first for the Haier Android TV in `tv-debloat` (a local project, not on GitHub) (this repo is a submodule there), whose stock quick menu only works on HDMI/Live TV and only offers picture mode.

Status: **design done** ([spec](docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md)). Nothing built yet.

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

Not set up yet. Plan: Android command-line tools + JDK on this Windows PC (no Android Studio needed), Kotlin, no third-party libraries unless needed. Install with adb: `adb install`, then the `pm grant` above.
