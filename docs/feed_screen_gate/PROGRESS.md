# Progress log — ad/subject keyword matching must respect word boundaries

## Investigation (2026-09-09)

Driver asked "the autoscrolling is still happening" and "do you have a
PRD and ralph loop and premortem specifically for this" after both
`docs/auto_scroll_hard_stop/` and `docs/skip_dedup_root_cause/` shipped
and merged (PR #1, `main` @ `2b85f13`). Driver then supplied a real
Diagnostic Log from the actual incident (`diagnostics10.log`, 762
lines).

Confirmed both earlier fixes are working exactly as designed - the
dedup guard suppressed 428 would-be-duplicate skips with zero real
double-swipes, and the hard-stop escalation correctly never fired
(nothing in this log looks like a rapid streak of real skips). Neither
is the cause of what's in this log.

Found instead: all 19 `AD matched` lines in the log matched the SAME
configured keyword, `"Ad"`. `FilterEngine.evaluate`'s ad-keyword check
is a plain substring test (`it.contains(keyword, ignoreCase = true)`),
so `"Ad"` also matches inside `"Add"` - and TikTok's own
`"Add or remove this video from Favourites."` chrome text is present on
663 of the log's 762 lines, essentially every video, ad or not. This
alone explains the dominant pattern in the log:

- A real, unrelated video (`tidyteachermum`'s "5 minute home reset")
  got skipped and then sat stuck, un-transitioned, for 1m28s while the
  dedup guard correctly suppressed ~290 further attempts on it.
- The comments panel, while open (`"Search:, gen z reacts to
  metallica, ..., Add comment...,"`), matched via `"Add comment..."`
  and fired a real skip while the driver had it open reading comments.
- The Android Recents/task-switcher screen (not TikTok at all -
  `"Podcast Addict"`, `"Dasher Monitor"`, `"Close all"`) matched twice
  via `"Ad"` inside `"Podcast Addict"` and fired a real skip gesture on
  a completely unrelated screen - directly contradicting this file's
  own class doc comment ("never reads or acts on anything outside
  [the target packages]"). Left as an open question (PRD §5) exactly
  why that event's `packageName` passed the target-package filter -
  the log proves it did, but not why; word-boundary matching removes
  the one way this was observed to cause a real skip, without claiming
  to have solved that separate question.

`FilterEngine.matchesSubject` has the identical bug (same substring
pattern), unexercised in this specific log but fixed alongside it for
consistency. `isLiveStream` was checked and confirmed to NOT have this
bug - it already does exact-element matching, and already has a test
for the "don't match embedded in a longer caption" case that ad
keywords and subjects never got.

Wrote `docs/feed_screen_gate/PRD.md` and `RALPH_PROMPT.md`, including a
premortem (§3a) - four risks flagged, not solved: non-ASCII/emoji
keywords may not have well-defined regex word boundaries; this fix
doesn't confirm or rule out the Recents-screen `packageName` leak as a
separate, still-open problem; a real ad match that still doesn't
advance TikTok (fire-and-forget `performSkipGesture`) is a different,
already-known, still-unaddressed gap this doesn't touch; and a driver
who wanted substring matching on purpose gets a silent behavior change,
worth a one-line Setup copy addition (not built here - scoped as an
open question, §5, since the PRD's own ralph loop didn't locate a
specific string resource without more context).

## Implementation (2026-09-09)

Added `FilterEngine.containsWholeWord(text, keyword)`: wraps the
(regex-escaped) keyword in `\b...\b`, case-insensitive, so it matches
only when the keyword appears as its own bounded word/phrase - not
merely as a substring of a longer word. Falls back to a plain substring
check if the keyword fails to compile as a regex (defensive - a
driver-entered keyword is untrusted input), matching this app's
existing stance of never crashing on TikTok's or the driver's own text.

Wired into both call sites that had the bug: `evaluate()`'s ad-keyword
check, and `matchesSubject()`. `isLiveStream` and blocked-creator
matching (`extractHandle` + normalized-handle equality) were left
untouched - neither has this bug (§1.5 of the PRD).

Added 5 new tests to `FilterEngineTest.kt`:
- `an ad keyword does not match when it only appears inside a longer word` -
  keyword `"Ad"` against the exact real chrome string from the log
  (`"Add or remove this video from Favourites."`) -> null.
- `an ad keyword still matches its own standalone list element` - the
  real ad-badge shape from the log (`"Ad"` as its own element) ->
  `SkipReason.AD`.
- `an ad keyword phrase still matches embedded in a longer sentence at
  a punctuation boundary` - `"Sponsored"` inside `"This is (Sponsored)
  content"`, exercising boundary logic against punctuation rather than
  only whitespace.
- `a subject keyword does not match when it only appears inside a
  longer word` - `"art"` inside `"party"` -> false.
- `a subject keyword still matches as its own word inside a longer
  caption` - `"art"` in `"I love art museums"` -> true.

Traced every new test AND every pre-existing keyword-matching test in
the file by hand against the new `containsWholeWord` implementation
before pushing (no Kotlin/JVM toolchain in this sandbox, same disclosed
limitation as every other PRD here) - specifically re-checked `ad
keyword match fires AD`, `Ad starts in countdown text fires AD`, `ad
keyword match is case-insensitive`, `blank ad keywords...`, both
`an ad keyword belonging to a preloaded/current video...` tests, and
all five pre-existing `matchesSubject` tests. None of them rely on
substring-inside-a-word matching (their fixture text already has the
keyword bounded by whitespace or string edges), so all continue to
pass unchanged under the stricter check.

## Verification

Pushed for the real CI (`.github/workflows/android-build.yml`) to
execute `./gradlew test` (including all 5 new tests plus the full
pre-existing suite) and `./gradlew assembleDebug`.

**Confirmed green**: both `build` check runs on commit `f045790`
completed with `conclusion: success`
(https://github.com/ddann74/tiktok-feed-filter/actions/runs/34325326359,
https://github.com/ddann74/tiktok-feed-filter/actions/runs/34325289582,
both completed 2026-09-09T07:45:5{1,4}Z) - the 5 new tests and the full
pre-existing suite all passed for real, confirming the hand-traced
logic was correct. PR #2's `mergeable_state` is `clean`.

Remaining PRD §6 boxes: driver confirms with a real Diagnostic Log, and
driver sign-off.
