# TV Quick Settings

An Android TV overlay app for quick picture/sound changes. Read README.md first: it has the user's description, what's known about the Haier, and the open questions.

This repo is a git submodule of `tv-debloat` (the parent folder). The parent's CLAUDE.md rules for working on a TV apply here too: reversible changes only, log TV setting changes in `../tvs/<tv>/changes.md`, and don't operate the TV while someone is watching.

## Next steps

1. Finish the design with the user (superpowers:brainstorming), including research on other brands' quick menus, and write it to `docs/superpowers/specs/`.
2. Plan (superpowers:writing-plans), then install the build tools (JDK + Android command-line tools) and build.

## TV access

- Haier: `adb connect 192.168.29.186:5555` (IP not reserved; find it with a port-5555 scan on 192.168.29.x). USB debugging may be off; ask the user to turn it on.
- Test a setting by writing it, asking the user what changed, and restoring the old value. Current values are in `docs/haier-global-settings.txt`.
