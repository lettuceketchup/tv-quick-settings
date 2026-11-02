# Plan review pass 4: emulator demo (2026-11-02)

The user asked for a demo of each UI step on a TV-sized emulator before anything goes on the TV. This added Task 5a (the harness) and demo steps 6.2a, 7.1a and 8.1a. A fresh reviewer checked the amendment. Checks run on the emulator while setting it up are marked *(measured)*.

| # | Finding | Disposition |
|---|---|---|
| H1 | `adb -e` means "the TCP/IP device", so with the TV connected it reaches the TV (pm clear, accessibility list, picture settings). *(measured: `-e` errored with "more than one emulator" while the TV was connected)* | Fixed: `adb -s emulator-5554` everywhere; the script aborts unless `ro.kernel.qemu` is 1. |
| H2 | Seeding from `haier-global-settings.txt` gives `picture_mode 0`, which breaks Task 7's expected pane values. | Fixed: a fixed seed list, always written, with `picture_mode 7`. |
| H3 | In PowerShell 5.1, `exec-out screencap -p > x.png` corrupts the PNG. | Fixed: `screencap` to /sdcard, then pull. |
| M1 | Injected keys probably skip `onKeyEvent`. *(measured: neither `input keyevent` nor `emu event send` reached the bound service; the image can't be rooted)* | Fixed: all scripted keys use `DEBUG_KEY` → `handleKey`, now built in Task 5a. The key filter is checked only on the TV. |
| M2 | The log can't show that a key was consumed. | Fixed: `handleKey` logs `consumed=`. |
| M3 | The execution policy blocks `powershell -File`. | Fixed: `-ExecutionPolicy Bypass`. |
| M4 | `hw.keyboard=no`, so the emulator window ignores the PC keyboard. | Fixed: set to yes (done). |
| M5 | A run after Task 8 starts on an emulator in standby. | Fixed: WAKEUP before HOME. |
| M6 | `DEBUG_SLEEP` was described in Task 8 Step 2 but needed in Step 1a. | Fixed: moved into Step 1. |
| L1 | The `--longpress` reasoning was wrong (API 30 holds about 0 ms). | Moot: holds go through `DEBUG_KEY`. |
| L2 | screenrecord takes about 1 s to start. | Fixed: wait 2 s after starting it. |
| L3 | PS 5.1 doesn't stop on a failing native command. | Fixed: check `$LASTEXITCODE`. |
| L4 | Pill screenshots land late in a 2 s pill. | Fixed: `wait 0.5` before pill shots in Task 6. |
| L5 | Task 8's timing fits in screenrecord's 180 s cap. | No change. |
| Cut | Separate `key`/`hold` step types; reading the settings file. | Cut: one `key <name> [holdMs]` step and a fixed seed. The README section stays, because the user asked to be able to drive the emulator. |

Also measured during setup: the emulator won't start without `platform-tools` inside the SDK (now installed), and `screenrecord` works on the image.

## Ponytail pass on the amendment (2026-11-02)

The user approved all five cuts, and they're applied to the plan.

| # | Cut | Replaced by |
|---|---|---|
| P1 | The key-name table in `demo.ps1` | `DEBUG_KEY --es key <name>`, resolved with `KeyEvent.keyCodeFromString` (API 29+ takes `MENU`, `KEYCODE_MENU` or `82`). |
| P2 | Task 1's raw key log kept alongside `handleKey`'s log | `handleKey`'s `consumed=` line only. |
| P3 | `SimView.kt` as its own file | A `private class` in `QuickService.kt`. |
| P4 | Committing `demo/probe.txt` | A scratchpad steps file; `task6.txt` covers the harness from Task 6 on. |
| P5 | The long emulator paragraph in Global Constraints | Two sentences pointing at Task 5a, which now holds the details. |

Left alone on purpose: the `ro.kernel.qemu` guard, `pm clear` before each run, the `$LASTEXITCODE` checks, video plus screenshots, the four step types, and SimView itself.
