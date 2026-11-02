# Haier TV tests (Task 0)

Date: 2026-11-02. Haier Android TV (Android 11, MediaTek m7322, Projectivy launcher), remote Haier HTR-U27E (Bluetooth, input device "HaierTV-RC", `/dev/input/event3`). Every write was restored and logged in `../tvs/haier/changes.md`.

## Remote keys

The remote's key layout file (`/vendor/usr/keylayout/Vendor_005d_Product_0001.kl`) can't be read over adb (permission denied), so the Android keycodes below are not confirmed. The raw HID usages are from `getevent -l` and the kernel log (`MTK_KL ... value=0xc0XXX`).

| Button | HID usage | Linux key | Android keycode |
|---|---|---|---|
| MENU (≡), short | 0x0C0071 | `KEY_UNKNOWN` | unknown; Android does map it (Projectivy has a "Menu double press" option) |
| MENU, held ~3 s | the remote releases 0x71, then sends **0x0C00BB** as a separate press | `KEY_UNKNOWN` | Android maps it: it opens Settings (see below) |
| Red / Green / Yellow / Blue | 0x0C003A / 3B / 3C / 3D | `KEY_UNKNOWN` (all four) | unknown |
| FAV | 0x0C00B9 | `KEY_SHUFFLE` | unknown |
| Home | 0x0C0223 | `KEY_HOMEPAGE` | `HOME` |
| Back | 0x0C0224 | `KEY_BACK` | `BACK` |
| Up / Down / Left / Right / OK | 0x0C0042 / 43 / 44 / 45 / 41 | `KEY_UP` … `KEY_SELECT` | `DPAD_*` |

What this means for the build:
- **The Linux codes can't tell the colour keys apart.** Android can only distinguish them if Haier's layout file maps the HID usages (`key usage 0x0c003a …`). Since 0xBB does reach Android as a working key, that's likely, but not proven. **First thing on the TV (Task 1's key log):** log `keyCode` and `scanCode` for MENU, the four colours and FAV, and fill in `keyOf` from that log. If the four colours arrive with the same keyCode, the presets are only reachable from the bar (as the spec says).
- **MENU long press needs no work from the app.** The remote itself turns a 3-second hold into a different key (0xBB). The app catches only the short MENU key and lets 0xBB through, and the TV opens Settings as it does today. The MENU key's own DOWN stays held for those 3 s, so the app must act on the MENU **UP** and must not open the bar when the hold lasted ≥ 1 s (measured from the key's down time: the app most likely gets no key repeats). Opening Settings from the app as well would open it twice.
- `SETTINGS_COMPONENT` = `com.android.tv.settings/.MainSettings` (what 0xBB opens). Only needed if the app opens Settings itself.
- A short MENU press currently does nothing visible (tested in Projectivy).
- **Projectivy runs its own accessibility service** that filters keys (`com.spocky.projengmenu/.services.ProjectivyAccessibilityService`, "Enable button remapping" on). Both services see each key. Projectivy's Back double press and "Menu double press" stay as they are.

## Key log (Task 1, 2026-11-02)

Logged by the probe `QuickService` in YouTube. These are the Android keycodes the app sees, and they replace the "unknown" column above.

| Button | keyCode | scanCode |
|---|---|---|
| MENU, short | 82 `MENU` | 240 |
| Red / Green / Yellow / Blue | 183 / 184 / 185 / 186 `PROG_RED` … `PROG_BLUE` | 240 |
| FAV | 93 `PAGE_DOWN` | 410 |
| Up / OK / Back / Home | 19 `DPAD_UP` / 23 `DPAD_CENTER` / 4 `BACK` / 3 `HOME` | 103 / 353 / 158 / 172 |

- **MENU reaches the app**, and the four colours have distinct keycodes. FAV isn't needed as a fallback.
- **No key repeats arrive.** Every press is one DOWN and one UP with `repeatCount` 0, even Up held 4.7 s.
- **The remote caps a hold at about 3 s.** It sends UP after 3.0 s (MENU 3026 ms, colours 3004–3016 ms) and then a **separate long-press key**, a 1 ms DOWN/UP, with scanCode 240:

| Held | then sends |
|---|---|
| MENU | 250 `TV_INPUT_COMPONENT_2`, which opens Settings |
| Red | 130 `MEDIA_RECORD` |
| Green | 127 `MEDIA_PAUSE` |
| Yellow | 86 `MEDIA_STOP` |
| Blue | 217 `RO`, which opens the firmware's "Magic Board" |

  For the build: a hold of 1–3 s releases normally. A hold of 3 s or more reaches the app as a 3 s press, then the follow-up key. The follow-up key passes through to the foreground app (Green's `MEDIA_PAUSE` would pause YouTube) unless the app consumes the one that arrives right after a long colour press.
- Short presses: 180–340 ms.
- **Projectivy still works** with both services on: its Back double press still starts `Somnambulator` (logcat 22:24:52). The screen flashes because the dream fails (Backdrop is disabled); repeat double presses right after show nothing.

## Standby (Task 1)

`adb shell am broadcast -a io.github.lettuceketchup.tvqs.DEBUG_STANDBY` → `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` returned true. Logcat: "Going to sleep due to accessibility"; `mWakefulness=Asleep`, display OFF. The remote's power button woke the TV normally, and adb over Wi-Fi came back after the wake. **This is the Sleep tile's standby method**; `attentive_timeout` isn't needed.

## Setting keys

All in `Settings.Global`. The firmware (`TVSettingConfig` in `com.mediatek.wwtv.tvcenter`) copies each key it knows into the MediaTek config (`g_video__…`, `g_fusion_sound__…`) as soon as it's written; logcat shows `setConfigValue`.

| Key | Result |
|---|---|
| `picture_backlight` (0–100) | **Live.** Kept separately **per picture mode** (User 51, the other modes 100) and **per input** (apps 51, HDMI 4 100). The key shows the current mode/input's value, and a write goes to the current one. |
| `picture_mode` | **Live**: writing it switches mode and loads that mode's settings. Values: **0 User, 2 Sport, 3 Vivid, 7 Standard, 9 Movie, 10 Game, 11 Energy Saving**. 1, 4, 5, 6 and 8 show a blank name. **Never write anything outside this list**: 12 and 13 overwrote the User mode's picture settings with 100s (fixed by writing them back). Menu order: User, Standard, Vivid, Sport, Movie, Game, Energy Saving. |
| `sound_style` | **Live**, loads the mode's balance/bass/EQ. **0 User, 1 Standard, 2 Night, 3 Sports, 4 Movie, 5 Music, 6 News** (= menu order). 7 and 8 show News; don't write them. |
| `picture_blue_light` | Stored by the firmware (`g_video__vid_blue_light`, takes any value, 100 included), but **no visible change** at 100 or 50. The TV has no blue-light option in its menus. |
| `picture_color_temperature` | Applied by the firmware (`g_video__clr_temp`). **0 User, 1 Cool, 2 Standard, 3 Warm**. Current: 2. Not checked by eye. |
| `sound_dialog_enhancement` | Passed to `g_fusion_sound__dialog_enhancement`, but there's **no such option in the TV's menus** and it wasn't checked by ear. |
| `hdmi_system_audio_control_enabled` | Sound output. **0 TV Speakers, 1 External Audio System** (Sound → Speakers follows it). No ARC device is connected now (`mArcEstablished: false`). Bluetooth audio isn't a setting. |
| `tv_timer_sleep_timer_entry_values` | The TV's Sleep Timer (Settings → Device Preferences → Power, also in the HDMI menu): 0 Off, 1–6 = 10–60 min in 10s, 7 = 90, 8 = 120. **Writing it starts nothing**: no alarm, and `getSleepTimerRemainingTime` stays 0. The menu sets the timer through the MediaTek API and only mirrors the choice into this key. |
| `power_picture_off` | **Works.** 1 = picture on, 0 = picture off (screen black, sound on). Power → Picture off writes 0, and a key press brings it back (1). Writing 0 then 1 from adb turned the screen off and on. |

Other things seen: the Power page also has Switch Off Timer and "No Signal Auto Power Off" (15 min). `g_video__vid_blue_mute` (`global:g_video__vid_blue_mute`) toggled when the user looked through the HDMI menu (blue screen on no signal, not useful).

## Picture off and standby (Task 6, 2026-11-03)

With the picture off by `power_picture_off` 0, `dumpsys power` still says `mWakefulness=Awake`: the TV doesn't treat it as standby. `adb exec-out screencap` doesn't capture an HDMI input's picture.

## Picture mode, warmth and the bar (Task 7, 2026-11-03)

- The TV menu and the app take the same path: both write the `Settings.Global` key, and the TV's config service (`TVSettingConfig`, in its own process) applies it with `setConfigValue`. Backlight, warmth and picture mode all work from the app.
- After a `picture_mode` change the service writes the new mode's `picture_backlight` (and `picture_mode_dolby`) about 0.4 s later, with a normal change notification. Until then the key still holds the old mode's value.
- Warmth: the TV writes the gains along with `picture_color_temperature`: Warm sets `picture_red_gain` 2 and `picture_blue_gain` −2, Standard sets both to 0. The user saw Cool, Standard and Warm differ, with Warm clearly warmer.
- Each mode keeps its own backlight; during these tests User mode ended up with Standard's 100 (cause not pinned down). The user reset it to 56 in the menu and doesn't mind test changes to these values.
- `screencap` shows YouTube's video area as black, so on-TV screenshots show the overlays only.
- A fast Back-Back (186 ms) while the bar had the keys didn't start Projectivy's screensaver; with the bar closed it does (and fails, see below).

## Screensaver (Ambient)

- **Projectivy already starts it**: "Ambient Mode on 'Back' double press" is on, and a Back double press launches `com.android.systemui/.Somnambulator` (seen in logcat). That covers what the Ambient tile was for.
- On screen, nothing appears, because the debloat disabled the screensaver: `com.google.android.backdrop` was disabled in batch 1. `screensaver_components` is empty, and the default component is Backdrop. `com.android.dreams.basic` (Colors) is still installed. Re-enabling Backdrop or picking Colors would make Back double press show something; that's the user's call.

## Night mode

The TV has no working blue-light control, and Android's Night Light isn't available (`dumpsys color_display`: "Night display: Not available"; it also wouldn't reach the hardware video layer). What's left:
- **Backlight**: the strongest night setting. Studies point to total light reaching the eye, not screen colour, as what delays sleep; Night Shift–style colour filters showed little effect.
- **Colour temperature Warm** (`picture_color_temperature` 3): the TV's own warm shift, applied by its picture chip, the same thing LG and Samsung sell as a low-blue mode. How much blue it removes is unknown.
- R/G/B gain (`picture_red_gain` / `_green_gain` / `_blue_gain`, under Color Temperature): graded, but the menu numbers say nothing about the real effect. Changing a gain switches Color Temperature to User. Not used for now.
- An app overlay would add orange light instead of removing blue and lift blacks, so it's not used.

The TV can't report what it emits: there's no sensor, and screenshots are taken before the colour processing. Real numbers need a colorimeter, or a phone camera in manual mode (locked white balance and exposure) comparing settings on a white screen.

**Decision (user, 2026-11-02):** a **Warmth** tile (User / Cool / Standard / Warm) replaces Blue light, and presets hold backlight + warmth.

## Tile results (confirmed by the user)

| Tile | Result |
|---|---|
| Backlight | Keep |
| Warmth | New, replaces Blue light: `picture_color_temperature` |
| Preset | Keep: backlight + warmth |
| Picture | Keep, with the value list above |
| Sound | Keep, with the value list above |
| Output | Keep (the user has an ARC audio system): TV Speakers / External audio system |
| Sleep | Keep. The TV's key can't start a timer, so the app counts down itself and puts the TV into standby (method checked in Task 1) |
| Screen off | Keep: `power_picture_off` 0 / 1 |
| Blue light | Dropped: no visible effect |
| Dialogue | Dropped: no such option on this TV |
| Ambient | Dropped: Projectivy's Back double press does it |
