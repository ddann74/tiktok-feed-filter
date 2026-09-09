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

## Follow-up investigation + implementation (2026-09-09)

Driver asked what the diagnostic log does and doesn't cover. Audited
every file's coverage (which files call `diagnosticLog.log`, whether
every real failure/decision path in the ones that don't actually needs
it) and found two more real gaps - written up as PRD §7, same
document since both trace back to the same audit and one directly
reuses this PR's own fix.

**`DownloadedVideoLocator.findRecentlyAddedVideo`**: no exception guard
at all - `contentResolver.query()` can throw (most plausibly a
`SecurityException` on a denied/revoked media-read permission), reached
via a main-thread `postDelayed` callback with no surrounding try/catch
either, meaning an uncaught exception there crashes the app with zero
diagnostic trace. Fixed by wrapping the call site in
`TikTokActionCoordinator.locateAndExtractAudio`: logs via
`diagnosticLog.logError` and gives up immediately (a permission error
won't fix itself by retrying `MAX_LOCATE_ATTEMPTS` times).

**`TikTokActionCoordinator.findAndClickNode`**: the exact same
plain-substring keyword-matching bug this PRD already fixed once for ad
keywords (§1.3), just not yet caught in a real log - used to find and
tap Block/Download/Live-menu buttons. Fixed by reusing
`FilterEngine.containsWholeWord` directly (changed from `private` to
`internal`) instead of duplicating the fix.

Applying the shared fix here surfaced a real edge case §1's own
investigation never needed to consider:
`DEFAULT_LIVE_MORE_OPTIONS_KEYWORDS` ships `"..."` (three literal dots)
as a real keyword. A keyword made entirely of punctuation has no
letters/digits, so wrapping it in `\b...\b` would never match anything
at all - `\b` requires a word character on at least one side of the
boundary. `containsWholeWord` now checks
`keyword.none { it.isLetterOrDigit() }` and falls back to plain
substring matching for that shape of keyword, closing the exact risk
PRD §3a's premortem P1 had already flagged as disclosed-but-not-solved
for the original ad/subject-keyword fix too.

Added one new test (`a punctuation-only keyword falls back to plain
substring matching, not word-boundary`, using the real `"..."`
default). Traced every real default keyword list in
`SettingsRepository` by hand against the new matching behavior - only
`"..."` needed the fallback, every other default still matches exactly
as before. `findAndClickNode` itself isn't independently
unit-testable (operates on real `AccessibilityNodeInfo`) - its
correctness now rides entirely on `containsWholeWord`, already fully
covered by `FilterEngineTest.kt`.

Pushed for the real CI to confirm.

**Confirmed green**: both `build` check runs on commit `ce3972e`
completed with `conclusion: success`, PR #3 merged as `89fb5b7`.

Remaining PRD §8 boxes: driver confirms, driver sign-off.

## Second follow-up (2026-09-09): "what won't the diagnostic log do now"

Driver asked what's still missing after §7/§8. Answered directly first
(the biggest one: nothing confirms a skip actually took effect - a
stuck video only ever produced silent duplicate suppression), then
driver asked to fix what's fixable now. Written up as PRD §9/§10, same
document.

Five changes:
1. **Stuck-video warning** - `stuckVideoWarningLoggedForIdentity` +
   `STUCK_VIDEO_WARNING_MILLIS` (5s, UNCONFIRMED). One `STUCK VIDEO`
   warning per stuck episode, not a retry or any other behavior change
   - scope discipline, not a new unverified recovery mechanism.
2. Media-locate crash guard - already shipped in §7.1/PR #3, restated
   only because the driver's question was asked before that PR merged.
3. **Unicode-aware word-boundary matching** - `(?U)` added to
   `containsWholeWord`'s regex. Two new Cyrillic tests
   (`кот`/`которая`). HONEST LIMIT found while reasoning through this,
   not glossed over: CJK scripts have no inter-word delimiter at all,
   so `\b` has nothing to find a boundary against regardless of this
   flag - a real, different, NOT-solved limitation, disclosed rather
   than silently claimed as fixed.
4. **`looksLikeFeedScreen`, diagnostic-only** - checks for TikTok's own
   "For You" tab label (confirmed present in every genuine feed read,
   absent from both real non-feed false positives in the original
   log). Deliberately does NOT gate/block a skip - considered it and
   rejected it: whether a Live room or an untested legitimate screen
   also lacks this marker is unconfirmed without a real device, and
   using it to suppress a skip risks silently breaking real filtering,
   a worse failure than the one it would catch. Logs a `WARNING:` line
   instead - same symmetric-visibility principle as everything else in
   this PRD.
5. **Device info + `onUnbind` logging** - `onServiceConnected` now logs
   manufacturer/model/sdk once per session; new `onUnbind` override
   logs on service disconnect. Direct port of the exact gap
   `dasher-monitor-`'s own `docs/watchdog_reliability/PRD.md` found and
   fixed for its own accessibility service this same session.

New tests: two Cyrillic word-boundary tests, three `looksLikeFeedScreen`
tests using real shapes from the original log (genuine feed, real
Recents-screen text, real comments-panel text). Traced every new and
pre-existing test by hand (no Kotlin/JVM toolchain in this sandbox, same
disclosed limitation as always) before pushing for the real CI to
confirm.

Remaining PRD §10 boxes: CI confirmation (update once green), driver
confirms, driver sign-off.

**Confirmed green**: both `build` check runs on commit `e1875fd`
completed with `conclusion: success`, PR #4 merged as `65bc9cf`.

## Third follow-up (2026-09-09): "still happening, also in comments, also outside the app" - diagnostics11.log

Driver reported the auto-scroll issue persisting in comments and
"outside of the app itself", with a new real log (`diagnostics11.log`).

**Important, flagged directly to the driver**: line 321-322's match (an
"Ad" skip on TikTok's own share-to bottom sheet, text list contains
only "Add to Story", never a standalone "Ad") is the exact signature of
the OLD plain-substring bug PR #2 already fixed. Verified with a real
regex engine (Python's `re`, not just hand-tracing) that `\bAd\b`
cannot match "Add to Story" - the current code on `main` could not have
produced this match. Strong signal the installed build predates PR #2
and hasn't been rebuilt/reinstalled since - flagged prominently rather
than chased further as a new code bug, since chasing a bug that's
already fixed but undeployed would waste both sides' time.

Two things ARE real regardless of build staleness: `looksLikeFeedScreen`
correctly flagged the share sheet and comments panel as non-feed
(WARNING fired as designed), and a ~101s stuck-video episode occurred on
a real ad (confirmed via a standalone "Ad" badge element in its text
list, not a substring match) - the largest stuck episode seen across
either log, and the underlying gesture-not-confirmed gap is still open
(§11.4/§12, deliberately not attempted this round - too large/risky to
rush).

**Fix shipped this round**: promoted `looksLikeFeedScreen` from
diagnostic-only (WARNING, PR #4) to an actual skip gate (`return`,
suppresses the skip) in `TikTokFilterService`, now that two separate
real logs show zero false positives and confirm exactly the screens
(comments panel, share sheet) the driver is reporting. Live rooms
unaffected (excluded earlier in the same function). No source change to
`FilterEngine.looksLikeFeedScreen` itself - only its doc comment and its
call site's behavior changed - so no new `FilterEngineTest.kt` cases
needed.

Pushed for the real CI to confirm.

**Confirmed green**: both `build` check runs on commit `9dd533e`
completed with `conclusion: success`
(https://github.com/ddann74/tiktok-feed-filter/actions/runs/34337350604/job/102419748861,
https://github.com/ddann74/tiktok-feed-filter/actions/runs/34337324477/job/102419665156).

Remaining PRD §13 boxes: driver rebuilds/reinstalls and confirms,
driver answers §12's two open questions, driver sign-off.

## Fourth follow-up (2026-09-09): stuck-gesture retry (§12's second question)

Driver asked to fix the stuck-gesture problem next. Written up as PRD
§14/§15, same document (continues §11.4/§12's own open question).

New `StuckVideoRetryGuard` (pure, `decide(retryCount,
elapsedSinceLastAttemptMillis, stuckThresholdMillis, maxRetries) ->
Retry/GiveUp/KeepWaiting`) wired into `TikTokFilterService`'s existing
duplicate-skip-suppressed branch: once the same 5s threshold the old
one-time warning already used has elapsed, retry the gesture (bounded
to 2 retries, 3 attempts total) instead of only ever warning once. The
give-up warning still fires exactly once per stuck episode, just after
retries are exhausted rather than immediately at the 5s mark.

Extracted the circuit-breaker trip-handling code (previously only in
the real-skip path) into a shared `circuitBreakerTripped(now)` private
method, used by BOTH the real-skip path and the new retry path -
deliberate design decision, not an oversight: a stuck-video retry storm
should trip the exact same runaway-pattern safety net a burst of real
skips would, rather than being a second, uncapped path that could
bypass it (see PRD §14a/P4 for the one real tradeoff this creates and
why it's accepted).

Also wired `performSkipGesture`'s `dispatchGesture` call with a real
`GestureResultCallback` (previously `null`) - `onCancelled` now logs a
previously-invisible dispatch-level failure; `onCompleted` deliberately
not logged (would add a line to every single skip, no diagnostic value
for the expected case).

New `StuckVideoRetryGuardTest.kt` (6 tests): keep-waiting below
threshold, retry with incrementing count (twice), give-up once
`maxRetries` used, give-up stays given up even far past the threshold,
and a full simulated stuck episode matching the PRD's own ~15s-to-give-up
math. Traced every test by hand against the implementation (no
Kotlin/JVM toolchain in this sandbox, same disclosed limitation as
always), and traced `TikTokFilterService`'s own wiring by hand too -
specifically re-checked that extracting `circuitBreakerTripped` didn't
change the real-skip path's behavior (same order: circuit-breaker
check, then the "matched" log line, then `lastSkipMillis`/
`lastSkippedVideoIdentity`/`stuckVideoRetryCount` updates), and that
`stuckVideoRetryCount` only ever resets on a genuinely NEW skip.

Pushed for the real CI to confirm.

**Confirmed green**: both `build` check runs on commit `084589e`
completed with `conclusion: success`
(https://github.com/ddann74/tiktok-feed-filter/actions/runs/34338508468/job/102423468519,
https://github.com/ddann74/tiktok-feed-filter/actions/runs/34338486606/job/102423397047).

Remaining PRD §15 boxes: driver confirms with a real stuck episode,
driver sign-off.

## Fifth follow-up (2026-09-09): the word-boundary fix wasn't actually working - diagnostics12.log

Driver confirmed they'd rebuilt/reinstalled since PR #7 and reported
autoscroll as top priority, plus "even autoscrolls while a post is
paused." New real log (`diagnostics12.log`) analyzed programmatically
(not by hand, specifically to rule out transcription error given how
high-stakes this finding is): 58 of 66 "AD matched \"Ad\"" events
(88%) have NO real word-boundary "Ad" anywhere in their text - the
only match is "Ad" as a substring of "Add" inside TikTok's own "Add or
remove this video from Favourites." chrome text, the EXACT bug
`containsWholeWord` was supposed to have already fixed.

Ruled out a stale build: this same log has `RETRY 1/2`/`RETRY 2/2` and
the updated `STUCK VIDEO` wording, both PR #7 features that cannot
exist without PR #2's fix also being present in the same binary (PR #7
is built on top of PR #2 in git history). So the deployed code has the
fix, but it isn't reliably working.

Best available explanation: `(?U)` (UNICODE_CHARACTER_CLASS), the
regex flag the original fix relied on, was already flagged UNCONFIRMED
in that PRD's own premortem ("no JVM available in this sandbox to
actually run this") - Android's ICU-backed regex engine most likely
doesn't honor it identically to desktop/server JVM regex, either
throwing (silently caught by containsWholeWord's own fallback, itself
the old substring bug) or matching incorrectly. Rather than chase the
exact mechanism further without real-device access, rewrote
`containsWholeWord` to not use `Regex` at all: manual `String.indexOf`
+ `Char.isLetterOrDigit()` boundary checks - a basic Unicode-aware
character classification, not a compiled-regex Unicode flag, so it
can't have the same class of platform-specific risk.

Traced every existing `containsWholeWord`-dependent test in
`FilterEngineTest.kt` by hand against the new implementation (word-
boundary, punctuation-only fallback, non-Latin-script Cyrillic,
CJK-limitation disclosure) - all still pass. Added two new tests: the
exact real fixture from this log (confirms it doesn't match under the
new implementation either) and a scan-forward case (an earlier
non-boundary "Ad"-shaped substring inside "Adding", a real standalone
"Ad" later in the same string - the loop must keep scanning past the
miss).

Also observed, documented but not fixed this round (PRD §16.3): the
feed-screen gate (PR #6) correctly suppressed skips on what turned out
to be the Android lock screen's clock widget during this same false-
positive episode - good news for "outside the app," but reopens the
still-unconfirmed question of why TikTok's own package produced that
event at all (a second real instance of the ss5 Recents-screen
mystery). 8 of 10 `swipe gesture CANCELLED` lines fire within ~200ms
of a RETRY - a real, likely-meaningful correlation, flagged for a
future round rather than guessed at now. The "autoscrolls while
paused" report wasn't confirmed or ruled out in this specific log.

Pushed for the real CI to confirm.
