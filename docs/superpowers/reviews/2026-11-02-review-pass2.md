# Plan review, pass 2: judging the ponytail findings

Date: 2026-11-02. Reviewer: Claude Opus 5.5. Judges each finding in `2026-11-02-ponytail-review.md` against pass 1 (`2026-11-02-review-pass1.md`).

"User's call" means the finding removes something settled in the mocks. My lean is given, but the decision is the user's.

## Claims checked

- **Finding 9's dp arithmetic is wrong.** `adb shell wm size` gives 1920×1080 and `wm density` gives 320. The screen is therefore **960 dp** wide, not 1280. With the plan's 3% insets that leaves about 900 dp. Nine tiles at 120 dp (1080 dp), plus a focused tile 1.3× wider, don't fit. Even 95 dp tiles would clip "Energy Saving". The spec's "about seven fit on screen" holds.
- **Finding 4, `postDelayed` vs `elapsedRealtime`.** `Handler` counts `uptimeMillis`, which stops in deep sleep. While the screen is on, the TV holds a display wake lock and doesn't deep-sleep, so both clocks run at the same rate during an active timer. The timer should be cancelled when the screen goes off anyway (pass 1 H4). With that, one-shot `postDelayed` is safe.
- **Finding 2, a held colour acting as a short press.** This works, but only if the follow-up rule is in Task 5. Right now it's only in Global Constraints (pass 1 H1). It also needs the follow-up check to come **before** the `covered` rule, or a 3 s Blue hold turns the screen off and back on (pass 1 H2). Hold detection stays either way, because a held MENU still must not toggle the bar.
- **Finding 3, "removing the pane removes the Projectivy double-Back check".** This is only true if edit mode is also cut: Back out of edit, then Back to close the bar, is a double Back too. The double-MENU check stays regardless. "Stepping 7 modes is too slow" isn't the real trade-off, because the pane takes the same number of presses. The difference is that **stepping on the tile applies every mode in between live** (each one loads that mode's picture settings), while the pane applies only the one you pick.
- **"About 4 of 9 code files, about 600 lines."** The plan has 9 code files (Tiles, Haier, SleepMath, BarLayout, Controller, Store, PillView, BarView, QuickService). Taking everything removes BarLayout, merges Haier, and removes Store (only if finding 2 is also taken), so 3 files, not 4. The line count can't be checked, because the plan has no code yet.
- **Review Focus items.** Item 5 goes away with finding 1. Item 3 changes with finding 2: a held colour applies the preset instead of saving it. Items 1, 2 and 4 stay.

## Verdicts

| # | Finding | Verdict | Reason |
|---|---|---|---|
| 1 | Drop edit mode, fixed `TILE_ORDER` | **User's call** (lean: accept) | This is the biggest single cut, and I'd have made the same suggestion (pass 1). It removes Task 4, `SaveLayout`, `EditTile`, Review Focus 5, three Controller tests and the hardest part of `BarView`. The scrolling row still has to stay (see 9). |
| 2 | Drop long-press save, constant presets | **User's call** (lean: keep save) | It's cheap once H1 is in, and it's the only way to tune presets without a rebuild. If it's cut, `Store.kt` goes too (if 1 is also taken), and the follow-up rule must stay. |
| 3 | Drop the list pane | **User's call** (lean: keep) | It doesn't save presses. What it gives is choosing a Picture/Sound mode without applying every mode in between. That's real, but small. |
| 4 | One-shot `postDelayed` instead of a 15 s tick | **Accept, modified** | Schedule standby, the last-minute pill and the reminders once, on `SetSleep` and `ExtendSleep`, and cancel everything on a new set or on screen-off. This also fixes pass 1 M1 (the dismissed warning coming back), since the warning fires once. Changes: keep a pure, tested `remindersAhead(startedMin, msLeft): List<Int>` (the `m < started` and `m·60000 < left` rule) in place of `reminderCrossed`. Compute "time left" from `endAt` each time the tile renders, since a bar kept open with key presses can outlive 8 s. Delete `lastMinute()`. |
| 5 | Drop the 60/30/15/5 reminders | **Reject** | They're in the spec and not marked as my call. After finding 4 they cost one loop over `remindersAhead`. |
| 6 | `fmtMinutes` → always `"N min"` | **Accept** | The spec doesn't fix the format. "Sleeping in 60 min" reads fine. Drops a branch and a test. |
| 7 | `segments` → `step = 10`; drop the segmented bar and the pill's level bar | **Modify**: accept the constant, **reject dropping the bars (user's call)** | There's one level tile, so a constant step is right. The vertical segmented bar and the pill's level bar are the settled look, and they're a few lines of drawing. |
| 8 | No icons, text only | **User's call** (lean: modify) | Settled in the mocks. A middle path: start with simple vector drawables (about 5 lines of XML each), and drop any icon that doesn't read on the TV. The point that Unicode glyphs may be missing from the TV font is fair, so don't rely on glyphs. |
| 9 | No scrolling row | **Reject** | The arithmetic is wrong (see above), so 9 tiles don't fit. Cheaper version: use the platform `HorizontalScrollView` with `smoothScrollTo` for the scrolling and centring, and show the two chevrons from `canScrollHorizontally`. No custom scrolling code. |
| 10 | Merge `Haier.kt` into `Tiles.kt` | **Accept** | One target, one file. Trivial either way. |
| 11 | Delete the Task 9 stub | **Accept** | Same as pass 1. Renumbering isn't needed: just remove the stub and leave Task 10 as is. |

"Left alone on purpose": agreed on all four.

## Compared with pass 1

- **Both reviews:** findings 1, 2, 3 and 8 (pass 1's "big cuts"), 7 (the constant), and 11.
- **Only this review:** 4 (one-shot scheduling), which is better than what I had and also fixes M1. Also 6 and 10.
- **Disagree:** 9 (wrong arithmetic) and 5 (cheap once 4 is in). 7's visual part is the user's.
- **This review missed:** all of pass 1's correctness findings, because it only looks for over-engineering: the follow-up rule missing from Task 5 (H1), Blue held 3 s turning the screen back on (H2), stale values after a Picture switch (H3), the timer outliving a manual power-off (H4), the invisible trapping bar in Task 6 (M3), the Screen off tile not setting `covered` (M4), HDMI being untested (M2), and the low items.
