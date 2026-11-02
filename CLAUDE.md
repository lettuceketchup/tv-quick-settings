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
9. **Next: execute the plan from Task 5.** Task 4 and Task 9 are intentionally empty.

## TV access

- Haier: `adb connect 192.168.29.186:5555` (IP not reserved; find it with a port-5555 scan on 192.168.29.x). USB debugging may be off; ask the user to turn it on.
- Test a setting by writing it, asking the user what changed, and restoring the old value. Current values are in `docs/haier-global-settings.txt`.
