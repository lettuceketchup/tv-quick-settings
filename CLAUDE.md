# TV Quick Settings

An Android TV overlay app for quick picture/sound changes. Read README.md first: it has the user's description, what's known about the Haier, and the open questions.

This repo is a git submodule of `tv-debloat` (the parent folder). The parent's CLAUDE.md rules for working on a TV apply here too: reversible changes only, log TV setting changes in `../tvs/<tv>/changes.md`, and don't operate the TV while someone is watching.

## Next steps

1. Design: done, see `docs/superpowers/specs/2026-11-02-tv-quick-settings-design.md`.
2. Plan: done, see `docs/superpowers/plans/2026-11-02-tv-quick-settings.md`. Execute it task by task (user chose to start dev in a fresh session; recommended: native execution via superpowers:executing-plans).
3. TV tests (Task 0): done 2026-11-02, see `docs/haier-tests.md`. Next is Task 1. Final tiles: Backlight, Warmth, Preset, Picture, Sound, Output, Sleep, Screen off, Edit.

## TV access

- Haier: `adb connect 192.168.29.186:5555` (IP not reserved; find it with a port-5555 scan on 192.168.29.x). USB debugging may be off; ask the user to turn it on.
- Test a setting by writing it, asking the user what changed, and restoring the old value. Current values are in `docs/haier-global-settings.txt`.
