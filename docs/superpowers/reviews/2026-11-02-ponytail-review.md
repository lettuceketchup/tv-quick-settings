# Ponytail review of the implementation plan

Date: 2026-11-02. Target: `docs/superpowers/plans/2026-11-02-tv-quick-settings.md` (Tasks 2–10; Tasks 0 and 1 were already done). Author: Claude Sonnet 5.5, using the `ponytail-review` skill at "full" level. **Nothing here has been applied.** The plan and spec are unchanged.

This review only hunts over-engineering. It says nothing about correctness, security or performance, and it was written by a weaker model than the one asked to check it. Treat every finding as a claim to test, not a decision.

## Instructions for the reviewing model

Do the three passes in this order. Don't skip the first one: its value is that it isn't anchored on the findings below.

1. **Independent review, before opening the findings section.** Read `CLAUDE.md`, the spec, the plan, `docs/haier-tests.md` and the current code under `app/`. Write your own review of the plan (correctness, missing steps, risky assumptions, over-engineering, anything else you'd change) to `docs/superpowers/reviews/2026-11-02-review-pass1.md`. Don't read the findings section of this file until that file is saved.
2. **Judge the ponytail findings.** For each finding below, write accept, reject or modify, with a reason, in `docs/superpowers/reviews/2026-11-02-review-pass2.md`. Check the claims in "Claims to verify" instead of trusting them. Compare against pass 1: note findings you'd have made yourself, findings you disagree with, and anything in your pass 1 that this review missed.
3. **Second independent review, after pass 2.** Re-read the plan as it would stand if your accepted changes were applied, and look for what both reviews missed, especially interactions between cuts (for example, which tests, interfaces or "Review Focus" items become dead or change). Write it to `docs/superpowers/reviews/2026-11-02-review-pass3.md`.

Then give the user one short summary and ask which changes to apply. **Don't edit the plan or spec before the user answers.** Several findings remove things the user settled through the interactive mocks (edit mode, the list pane, long-press-save of presets, icons, scrolling row), so those need the user's call, not just yours.

## Findings

Format: `location: tag what. replacement.` Tags: `delete` (dead or speculative), `yagni` (abstraction or feature with no current need), `shrink` (same logic, fewer lines).

### Biggest cuts

1. `Task 4 + Store.kt + Edit tile + edit mode in Tasks 5 and 7`: yagni: a hide/reorder editor with its own encode/decode, persistence, "all hidden" focus edge case, "Moving" lift, dashed borders and eye-off marks. Only the user edits this bar, on one TV. Replace with `val TILE_ORDER = listOf(...)` in the tile file. If it's wanted later, make it an optional Task 11 after everything else works.
2. `Task 5/6: SavePresets, long-press-save, Store "presets"`: yagni: the spec calls this "*my call; the mocks may change it*". Use the `DEFAULT_PRESETS` constants and change them in code. A held colour then acts as a short press. Keep the rule that consumes the remote's follow-up key (Red 130, Green 127, Yellow 86, Blue 217), because a held Green would otherwise send MEDIA_PAUSE and pause the video.
3. `Task 5/7: paneIndex, hasPane, "OK ⋯", list pane`: delete: Up/Down already steps through all 7 Picture and Sound modes, with ▲ previous / current / ▼ next shown on the tile. Removing the pane also removes review item 10 in Task 7 (the Projectivy double-Back conflict), because the only reason Back is pressed twice is closing the pane and then the bar.
4. `Task 8 Step 1: 15 s tick loop, reminderCrossed, prev/now ms`: shrink: schedule each event once with `Handler.postDelayed` when the timer starts or extends (standby at `endAt`, the last-minute pill at 60 s left, and any reminder with `m < started`). Cancel and reschedule on `SetSleep` and `ExtendSleep`. Compute `minutesLeft(endAt - now)` when the bar opens; the bar closes after 8 s, so it needs no live re-render. Delete `reminderCrossed`, the "exactly at the 1-min mark" special case and `lastMinute()`.

### Smaller cuts

5. `Task 3: reminders at 60/30/15/5`: delete: only the last-minute warning stops the TV turning off on someone who is watching. The four reminder pills are polish; skip them for now.
6. `Task 3: fmtMinutes`: shrink: `"$min min"` always ("90 min"). Drops the hours/minutes branching and its test.
7. `Task 2: LevelTile.segments`: shrink: replace with `step = 10` and `stepLevel(percent, step, up)`. Drop the 10-block vertical bar and the pill's 4dp level bar; the number and ▲▼ already show the level.
8. `Task 7: "vector drawables you draw yourself"`: delete: no icons, only the tile name and value as text. Saves about 10 drawable files, and Unicode glyphs may be missing from TV fonts anyway.
9. `Task 7: scrolling row, centring, chevrons`: delete: 9 tiles at about 120dp is about 1080dp, and a 1080p Android TV is about 1280dp wide, so they fit in one `LinearLayout` row. Measure the real dp first. Keep wrap-around focus (`wrapFocus`).
10. `Task 2: Haier.kt split from Tiles.kt`: yagni: Haier is the only target (the spec's non-goals rule out the Hisense). Put it in one file.
11. `Task 9`: delete: an empty task kept "so the other tasks don't move". Drop it and renumber.

### Left alone on purpose

- The pure `Controller` with `Effect`s: it's what makes the JVM tests possible.
- Task 5's tests, which leave one runnable check per piece of logic.
- The accessibility overlay and the key-handling rules.
- The DEBUG receivers.

Estimated saving if everything is taken: about 4 of 9 planned code files (`BarLayout`, `Store`, the pane and icon pieces, the tick loop) and roughly 600 lines. This is a guess, not a count.

## Claims to verify

- The dp arithmetic in finding 9: the Haier's real resolution and density (`adb shell wm size`, `wm density`), and whether 9 tiles with a focused 1.3× tile fit in one row with room for the longest name.
- Finding 4: `Handler.postDelayed` counts `uptimeMillis`, which stops in deep sleep. Check whether that matters here (the timer exists to put the TV into standby, and the plan's own `SystemClock.elapsedRealtime()` for `endAt` differs from it).
- Finding 2: that treating a long colour press as a short press still behaves correctly with the follow-up-key rule (500 ms window after UP, 3 s remote hold cap), including Blue (screen off, then Magic Board).
- Finding 3: that nothing else needs the pane (for example, whether stepping 7 modes one at a time is too slow in practice, which is the user's call).
- The line and file-count estimate above.
- Whether any cut changes what "Review Focus" items 1–5 in the plan cover. Item 5 (all tiles hidden) disappears with finding 1.
