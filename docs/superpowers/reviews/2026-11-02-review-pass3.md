# Plan review, pass 3: the plan with the accepted changes applied

Date: 2026-11-02. Reviewer: Claude Opus 5.5. This pass re-reads the plan as if these were applied: pass 1's fixes H1–H4, M1–M4 and L2–L4, plus the accepted ponytail findings 4 (modified), 6, 7 (the constant only), 10 and 11, and finding 9 rejected in favour of `HorizontalScrollView`. The user's-call findings (1, 2, 3, 8) are checked both ways.

## New problems from combining fixes

**P1. H4's screen-off receiver may break Screen off and the sleep timer.** H4 cancels the sleep timer and clears `covered` on `ACTION_SCREEN_OFF`. If `power_picture_off 0` makes Android report the screen as off (it may only mute the panel, but this hasn't been checked), then:
- turning the picture off would clear `covered` at once, so the next key isn't swallowed;
- it would also cancel the sleep timer, which breaks "music with the screen off, then sleep", the main reason to use both together.

*Check before relying on H4* (Task 6 Step 4.4, TV free): after Blue, run `adb shell dumpsys power | grep -i wakefulness`. If it stays `Awake`, H4 is fine as written. If not, react to `mWakefulness` going to `Asleep`. In code, check `PowerManager.isInteractive` in the receiver and ignore the broadcast while it's still interactive.

**P2. `sleepLeftMin` must be set before every key, not only at render.** Finding 4 computes the time left from `endAt` when the tile renders. But the Controller also uses `sleepLeftMin` for `nextLonger` and `nextShorter` when Up or Down is pressed. The service should set `controller.sleepLeftMin = minutesLeft(endAt - now)` before each `onKey` call. That one line covers rendering too.

**P3. Extending re-arms the reminders right away.** With one-shot scheduling, `ExtendSleep` in the last minute of a 60-min timer leaves about 16 min, and `remindersAhead(60, 16 min)` gives [15, 5]. So "Sleeping in 15 min" fires about a minute after extending. That's fine and informative; noting it so the builder doesn't treat it as a bug.

**P4. The follow-up rule must see every key.** H1 checks "the event right before". That works only if the service calls `onKey` for **every** key, `OTHER` included, which Task 6 Step 1 does. Keep it that way, and don't short-circuit `OTHER` in the service.

## Things neither review covered

- **TV checks for H1 and H2.** Task 6 Step 4.2 holds Green for 2 s, which is too short to trigger the follow-up. Add: hold Green 4 s in YouTube, and the video keeps playing. Hold Blue 4 s: the picture stays off, and Magic Board doesn't open.
- **TV check for H3.** Add to Task 7: switch Picture in the bar, and the Backlight tile shows that mode's backlight. Warmth may also be stored per mode, so H3's refresh should re-read every value, not only backlight.
- **The `HorizontalScrollView` must stay non-focusable.** The Controller owns the focus. Scroll with `smoothScrollTo` to the focused tile, and never `requestFocus`, which would fight the `FLAG_NOT_FOCUSABLE` overlay.
- **`DEBUG_SLEEP` must go through the same scheduler** as `SetSleep` (with a millisecond value), or finding 4's one-shot posts won't be set up when testing.
- **Review Focus 3** should add "and the remote's follow-up key is swallowed".

## What changes if the user takes each of their findings

| If taken | What else changes |
|---|---|
| 1 Edit mode | Delete Task 4, `TileId.EDIT`, `UiState.editing`/`held`, `Effect.SaveLayout`, and the edit routing rule. The tests `editToggleAndMove`, `timeoutIgnoredWhileEditing` and `allHiddenFocusValid` go. `leftFromFirstGoesToEdit` becomes "goes to Screen off". Task 7 Steps 2.3, 2.5 and 2.6 change, as do Review Focus 5, the "except in edit mode" constraint, the spec's "Editing the bar" section and the Edit tile. The scrolling row stays. |
| 2 Long-press save | Delete `Effect.SavePresets`, `Store`'s `presets` key and Task 6 Step 4.2. `redHoldSavesIntoNightWithoutApplying` becomes "a held Red applies Night and swallows 130". Review Focus 3 changes. The H1 and H2 rules and hold detection stay (MENU still needs them). |
| 1 and 2 together | `Store.kt` and SharedPreferences go entirely, and the app keeps no state. |
| 3 Pane | Delete `hasPane`, `paneIndex` and the `"OK ⋯"` copy, plus three tests (`okOnPictureOpensPane…`, `panePickWritesAndCloses`, `backClosesPaneThenBar`) and Task 7 Step 2.4. The Projectivy double-Back check goes only if 1 is also taken; the double-MENU check stays. With no pane, every Up/Down on Picture writes `picture_mode`, so H3's refresh runs on each step. |
| 8 Icons | Only Task 7's view and drawables. Nothing in the logic. |

## Verdict

With the pass 1 fixes in, the plan is sound to execute from Task 2. The four correctness fixes (H1–H4) matter more than any of the cuts. P1 must be checked on the TV before H4 is relied on.
