# TV Quick Settings

An Android TV overlay app for quick picture/sound changes. Read README.md first: it has the user's description, what's known about the Haier, and the open questions.

This repo is a git submodule of `tv-debloat` (the parent folder). The parent's CLAUDE.md rules for working on a TV apply here too: reversible changes only, log TV setting changes in `../tvs/<tv>/changes.md`, and don't operate the TV while someone is watching.

## Next steps

1. Design: done, see `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md`.
2. Plan: done, see `docs/superpowers/plans/2026-11-02-tv-quick-settings.md`. Execute it task by task (user chose to start dev in a fresh session; recommended: native execution via superpowers:executing-plans).
3. TV tests (Task 0): done 2026-11-02, see `docs/haier-tests.md`. Final tiles: Backlight, Warmth, Preset, Picture, Sound, Output, Sleep, Screen off (Edit was dropped in the plan review).
4. Task 1 (toolchain and key-logging probe): done, committed.
5. Plan review: done 2026-11-02, applied to the plan and spec. The three passes are in `docs/superpowers/reviews/` (`2026-11-02-review-pass1.md` to `-pass3.md`). The user approved the four correctness fixes (H1–H4), the accepted ponytail cuts, dropping edit mode, and keeping long-press save, the list pane and drawn icons.
6. Task 2 (tiles, steps, presets): done, committed. 14 unit tests in `TilesTest.kt`.
7. Emulator demo added to the plan 2026-11-02 (Task 5a, and demo steps in Tasks 6–8; review in `docs/superpowers/reviews/2026-11-02-review-pass4.md`). The `haier_tv` emulator is installed; start it from a terminal tab. Use `adb -s emulator-5554`, never `adb -e` (that can hit the TV).
8. Task 3 (sleep timer maths): done, committed. 12 unit tests in `SleepMathTest.kt`.
9. Task 5 (controller): done, committed. 25 unit tests in `ControllerTest.kt`. The user changed two of the builder's calls (the bar opens on the first tile; Left/Right leave a pane); the decisions are noted under Task 5 in the plan.
10. Task 5a (emulator demo harness): done, committed. `tools/demo.ps1 demo/<name>.txt`; see README "Try it on the emulator". The emulator window's arrows, Enter and Esc reach the app.
11. Task 6 (service wiring, colour-key presets, screen off, corner pill): done 2026-11-03, emulator demo and all seven TV checks passed; result under Task 6 in the plan.
12. Task 7 (the bar view): done 2026-11-03, TV checks 1–7 and 9 passed, 8 (Output with an ARC system) open for lack of one; fixes for lag, picture-mode values and the pane jump are under Task 7 in the plan.
13. Task 8 (sleep timer runtime and standby): done 2026-11-03, emulator demo and all four TV checks passed; result and the picture-off fix under Task 8 in the plan.
14. Task 10 (release build, full check, hand-over): done 2026-11-03. The release build is on the TV; the full check and the USB-debugging-off reboot passed. The user also asked for a restyled bar (pill tiles), smoother animations, a chunked backlight bar and a pane that stays open after a pick; see Task 10 in the plan. Task 4 and Task 9 are intentionally empty.
15. **Next: the final whole-branch review** (superpowers:executing-plans, fresh reviewer), then push `main` once the user approves. Open: Task 7 check 8 (Output with an ARC system), and whether a held Blue should do nothing (it turns the picture off on release now).
16. Later (user, 2026-11-03): fade the partly visible tiles at the row's ends again. Don't bring back the scroll view's fading edges (their offscreen layer held scrolling to 30 fps on the TV); try a fixed strip-coloured gradient view over each end instead, and measure with `dumpsys gfxinfo framestats`.

## TV access

- Everything known about the Haier and its remote (photo, key codes, settings keys and their quirks) is in `../tvs/haier/reference.md`. Add new findings there.

- Haier: `adb connect 192.168.29.186:5555` (IP not reserved; find it with a port-5555 scan on 192.168.29.x). USB debugging may be off; ask the user to turn it on.
- Test a setting by writing it, asking the user what changed, and restoring the old value. Current values are in `docs/haier-global-settings.txt`.
