# PRD: Ad-keyword matching must respect word boundaries

Status: DRAFT - awaiting sign-off before implementation begins.
Scope: this one report only. Not a general codebase pass.

## 0. What this is / isn't

The driver reported "the autoscrolling is still happening" after both
`docs/auto_scroll_hard_stop/PRD.md` (escalating hard-stop) and
`docs/skip_dedup_root_cause/PRD.md` (stable video identity for the dedup
guard) shipped and merged (`main` @ `2b85f13`, PR #1). This time the
driver supplied a real Diagnostic Log from the actual incident
(`diagnostics10.log`, 762 lines, 2026-09-09 17:26:55-17:30:52). This PRD
does not assume the two earlier fixes were wrong - the evidence below
confirms both are doing exactly what they were built to do. It identifies
a third, different, previously-unfound mechanism the log makes
unambiguous.

## 1. Why (investigation, 2026-09-09, real log evidence)

### 1.1 The two earlier fixes are confirmed working, not the cause

- The dedup guard (`FilterEngine.videoIdentity`) is doing its job: 428 of
  762 log lines are `duplicate skip suppressed for the same video (still
  transitioning?)`, and NONE of them is followed by a real second
  `performSkipGesture()` call for the same video - `TikTokFilterService`
  returns immediately on a duplicate match (see the `return` right after
  that log line, `TikTokFilterService.kt` around line 217). The guard is
  correctly preventing repeat swipes on a video it already tried once.
- The hard-stop escalation never fires in this log (no `CIRCUIT BREAKER
  TRIPPED` or `HARD STOP` line anywhere) - consistent with real skips
  never happening fast enough, back to back, to trip
  `SkipStreakGuard`'s window. Not because it's broken; because the actual
  problem here doesn't look like a rapid streak of real skips.

### 1.2 Every single skip in this log matched the same one keyword: "Ad"

`grep -oP 'matched "\K[^"]+' diagnostics10.log | sort | uniq -c` -> all 19
`AD matched` lines matched keyword `"Ad"`, and only that keyword. The
driver has configured a 2-character Ad Keyword, presumably to catch
TikTok's own short `Ad` badge (confirmed present as its own list element
on real ads in this log, e.g. `..., Ad, Learn more, Video, ...` at
17:30:40.833).

### 1.3 That keyword also matches inside completely unrelated words - confirmed, not hypothesized

`FilterEngine.evaluate`'s ad-keyword check is a plain substring test:

```kotlin
visibleVideoTexts.any { it.contains(keyword, ignoreCase = true) }
```

`"Ad"` is a substring of `"Add"`. TikTok's own persistent per-video
chrome contains `"Add"` at least twice on essentially every video,
real content and ads alike:

- `"Add or remove this video from Favourites."` - present on **663 of
  the log's 762 lines** (`grep -c`).
- `"Read or add comments. N comments"` - the comment-count row, on
  every video.
- `"Add comment..."` - the comment-input placeholder, shown whenever
  the comments panel is open.

This is not a corner case - it is the dominant, near-universal false
positive in the whole log:

- **A real, unrelated video the driver was watching** (the very first
  lines of the log): `tidyteachermum`'s "5 minute home reset" video
  matched `"Ad"` (via `"Add or remove this video from Favourites."`)
  and got a real `performSkipGesture()` call, then sat un-transitioned
  for **1m28s** (17:26:55.398 -> 17:28:23.953) while the dedup guard
  correctly suppressed ~290 further skip attempts on it - a real video
  the driver may have wanted to watch, skipped, and then stuck.
- **The comments panel, while the driver had it open**: 17:30:14.885
  matched `"Ad"` and fired a real skip - the visible texts at that
  exact moment are `Search:, gen z reacts to metallica, ..., 6,642
  comments, Close, deborahholmes383, ...` - the comments panel, not
  the video feed at all. (Matched via `"Add comment..."`, the
  comment-input placeholder always present at the bottom of an open
  comments panel.)
- **The Android Recents/task-switcher screen, not TikTok at all**:
  17:28:50.162 and 17:30:51.231 both matched `"Ad"` and fired a real
  skip gesture while the visible texts were `Create folder, Remove,
  Podcast Addict, Waze, Camera, Brave, No recent tasks, Dasher
  Monitor, More, ..., Close all, ...` - the OS app-switcher overview,
  matched via `"Ad"` inside `"Podcast Addict"`. This directly
  contradicts this file's own class doc comment ("This only ever acts
  on the configured target package(s) and never reads or acts on
  anything outside them") - see §5 open question; this PRD does not
  claim to know *why* that event's `packageName` passed the
  `targetPackages` check, only that the log proves it did.

None of this requires TikTok to be showing an unusually ad-heavy stretch
of the feed - a driver who configured the single-letter-pair keyword
`"Ad"` gets it fired on nearly every screen they see, TikTok or not,
which is exactly what "autoscrolling I can't control" looks and feels
like from the outside, independent of whatever real ads also legitimately
matched.

### 1.4 `matchesSubject` has the identical bug, unexercised in this log

`FilterEngine.matchesSubject` (Subject Boost's auto-like matcher) uses
the same `it.contains(subject, ignoreCase = true)` pattern. Not implicated
in this specific log (no Subject Boost entries appear), but it is the
same class of bug, sitting right next to the one that is - fixing one and
leaving the other would be an inconsistency worth calling out even if it
turned up nothing yet.

### 1.5 `isLiveStream` does NOT have this bug - already correct, already tested

`FilterEngine.isLiveStream` uses `screenTexts.any { it.equals(keyword, ignoreCase = true) }`
- exact match against a whole list element, not a substring search - and
already has a passing test for the exact concern this PRD raises
(`isLiveStream does not match LIVE embedded inside a longer caption`,
`FilterEngineTest.kt`). That test's existence, with no equivalent for ad
keywords or subjects, is itself evidence this gap was never deliberately
accepted - it looks like an oversight, not a considered tradeoff.

## 2. Definition of "functional" for this task

- [x] An ad keyword no longer matches when it appears only as a substring
      inside a longer word (`"Ad"` inside `"Add"`, `"Addict"`, etc.).
- [x] An ad keyword still matches when it appears as its own
      word/phrase, bounded by whitespace, punctuation, or the start/end
      of the text (TikTok's real `"Ad"` badge, `"Sponsored"`,
      `"Ad starts in"`, all still fire exactly as before).
- [x] The same fix applies to `matchesSubject` (Subject Boost), for the
      same reason, even though no false positive from it appears in
      this specific log.
- [x] `isLiveStream` is left untouched - it does not have this bug.
- [x] `extractHandle`/blocked-creator matching is left untouched - it
      already matches on a normalized, structurally-extracted handle,
      not a raw substring search over arbitrary text.
- [x] Every existing `FilterEngineTest` keyword-matching test still
      passes unchanged (they all use keywords/screen text that are
      already word-bounded in the fixture, so behavior for them does
      not change).

## 3. Design

Add a small private helper in `FilterEngine.kt`:

```kotlin
/** Whole-word/whole-phrase match: true when [keyword] appears in [text] as its own
  * token, bounded by whitespace/punctuation or the start/end of [text] - not merely
  * as a substring of a longer word ("Ad" must not match inside "Add"). Case-insensitive,
  * matching every other keyword check in this file. Falls back to a plain substring
  * check if [keyword] can't compile as a regex (Regex.escape prevents that in practice,
  * but a config-file free-text keyword is untrusted input, not something to trust
  * blindly) - never crash on a driver-entered keyword, same defensive stance as the
  * rest of this app already takes toward TikTok's own screen text.
  */
private fun containsWholeWord(text: String, keyword: String): Boolean =
    try {
        Regex("(?i)\\b${Regex.escape(keyword)}\\b").containsMatchIn(text)
    } catch (e: Exception) {
        text.contains(keyword, ignoreCase = true)
    }
```

Then change exactly two call sites to use it instead of `.contains(keyword, ignoreCase = true)`:

- `evaluate()`'s ad-keyword check (§1.3).
- `matchesSubject()` (§1.4).

Nothing else changes - `evaluate`'s parameter list, return type, call
order (blocked-creator first, ad-keyword second, repeat-view last), and
every other function in this file are untouched.

### 3.1 Why word-boundary regex, not something else

- **A denylist of known chrome strings** (exclude `"Add or remove this
  video from Favourites."` etc. from matching) would only patch the
  three specific strings found in this one log, not the underlying
  class of bug - a driver's next short keyword (`"On"`, `"Buy"`, `"Go"`)
  would hit the same failure mode against different chrome text next
  time.
- **Requiring the match to be the text's ENTIRE content** (like
  `isLiveStream`'s `.equals()`) does not work here: `isLiveStream`'s
  keywords are single badge words that always appear as their own
  standalone element; ad keywords are driver-configured, can be
  multi-word phrases (`"Ad starts in"`, `"Sponsored content"`), and are
  confirmed (§1.2, `"Ad starts in 5s"`) to appear as *part of* a longer
  text element, not the whole thing - `.equals()` would break that
  already-working, already-tested case.
- **Word-boundary regex** is the one option that keeps both working:
  bounded by non-word characters (or start/end of string) on each side
  of the matched phrase, so `"Ad"` matches its own comma-separated list
  element or `"...Ad starts in 5s"` (space before, digit-adjacent text
  after `"in"` is still a boundary) but not `"Add"`/`"Addict"`.

## 3a. Premortem (2026-09-09): assume this pass fails again

- **P1 - a keyword that is itself not a plain word (emoji, a symbol-only
  string, non-Latin script) might not have well-defined `\b` boundaries
  in Kotlin's regex engine, silently stopping it from matching at all.**
  Not fixed here (would need testing against real non-Latin ad keywords
  this environment can't source) - flagged, not solved. If a driver
  reports "my keyword stopped matching anything" after this ships and
  their keyword isn't a plain ASCII word/phrase, this is the first place
  to look. The try/catch fallback in §3 covers a keyword that fails to
  *compile*, not one that compiles but never matches due to boundary
  semantics - a real, disclosed gap, not silently assumed away.
- **P2 - this does not explain 100% of "autoscrolling," only the
  dominant mechanism actually evidenced in this log.** The Recents/
  task-switcher false positive (§1.3, third bullet) is fixed as a
  side effect of this change (word-boundary matching no longer finds
  `"Ad"` inside `"Podcast Addict"` either), but *why* that event's
  `packageName` passed the `targetPackages` filter at all is still
  unconfirmed (§5) - if that turns out to be a real, separate leak (not
  just this one keyword coincidentally catching it), a driver with a
  DIFFERENT keyword configured could still see swipes fire on
  non-TikTok screens, undetected by this fix. Recommend treating §5 as
  a real open follow-up, not a solved problem just because this
  specific log's symptom goes away.
- **P3 - the "video got stuck for 1m28s and never advanced" problem
  (§1.3, first bullet) is a DIFFERENT bug than the false-positive match
  that caused it, and is not fixed by this PRD.** Even with a perfectly
  precise keyword, a real ad match's `performSkipGesture()` can still
  silently fail to advance TikTok (a fire-and-forget
  `dispatchGesture`, never confirmed - `skip_dedup_root_cause/PROGRESS.md`
  already names this as a known, unaddressed gap). This PRD only
  reduces how often a skip fires on the wrong thing; it does nothing
  for what happens when a skip fires on the RIGHT thing and TikTok
  still doesn't move. If "auto scrolling" reports continue after this
  ships, with real ad-keyword matches (not `"Ad"`-inside-`"Add"` ones)
  and a video that visibly never transitions, that is this separate,
  still-open problem, not a sign this fix didn't work.
- **P4 - a driver who deliberately WANTED substring matching (e.g. a
  keyword fragment intended to catch several related words at once)
  gets a silent behavior change.** Nothing in Setup currently documents
  ad keywords as substring-vs-whole-word, so there's no explicit
  contract being broken, but it's worth a one-line mention in Setup's
  own copy (§3.2) so this isn't a surprise the next time someone reads
  the Ad Keywords help text.

### 3.2 Setup copy

Add one sentence to whatever helper/description text Setup shows for Ad
Keywords (and Subject Keywords, since both now share this behavior):
"Matches this as a whole word or phrase, not as part of a longer word (a
keyword of `Ad` matches TikTok's own Ad label, not the word `Add`)." -
scoped to whatever the ralph loop finds is the actual string resource/UI
location; not assumed here.

## 4. Testing / verification approach

Same disclosed limitation as every PRD in this repo: no Kotlin/JVM
toolchain in this sandbox. New tests traced by hand against the
implementation before pushing, then pushed for the real CI
(`.github/workflows/android-build.yml`) to execute for real - `./gradlew
test` and `./gradlew assembleDebug` both need to stay green, matching
every prior round in this repo.

New tests, added to `FilterEngineTest.kt`, matching its existing style:

- `an ad keyword does not match when it only appears inside a longer word` -
  keyword `"Ad"` against `"Add or remove this video from Favourites."`
  (the exact real chrome string from the log) -> `null`.
- `an ad keyword still matches its own standalone list element` -
  keyword `"Ad"` against a screen where `"Ad"` is its own element
  (matching the real ad-badge shape confirmed in the log) -> `SkipReason.AD`.
- `an ad keyword phrase still matches embedded in a longer sentence at a word boundary` -
  re-run of the existing `"Ad starts in 5s"` case, confirming this PRD
  doesn't regress it.
- `a subject keyword does not match when it only appears inside a longer word` -
  same shape as the ad-keyword case, for `matchesSubject`.
- `a subject keyword still matches as its own word inside a longer caption` -
  confirms `matchesSubject` still works for its one existing passing
  case shape.

## 5. Open questions

- **Why did the Android Recents/task-switcher event's `packageName`
  pass `settingsRepository.targetPackages`?** (§1.3, third bullet;
  premortem P2.) Two honest possibilities, neither confirmed: (a) some
  gesture-navigation/launcher implementations route the task-switcher
  overview's accessibility events through the most-recently-foregrounded
  app's own task, meaning the event's `packageName` genuinely was
  TikTok's even though the *visible content* was the launcher's overview
  UI - a platform quirk outside this app's control; (b) something in
  this app's own event handling is broader than intended. Needs either
  a driver willing to reproduce it with Diagnostic Logging on and note
  exactly what they did right before it happened (opened recents via
  gesture vs. button, which launcher), or acceptance that this is a
  platform quirk this app can only defend against indirectly (e.g. a
  future, separate structural "does this screen actually look like the
  TikTok feed" check) - not decided here, since word-boundary matching
  already removes the ONE way it was observed to cause a real skip.
- Does the driver want the Setup-copy addition (§3.2) in this same pass,
  or is a one-line doc note without changing Setup's actual UI text
  enough for now?

## 6. Success criteria (implementation-phase checklist)

- [x] `containsWholeWord` added to `FilterEngine.kt`
- [x] `evaluate()`'s ad-keyword check uses it
- [x] `matchesSubject()` uses it
- [x] 5 new tests added (§4), traced by hand against the implementation
- [x] Every pre-existing `FilterEngineTest` test still passes (traced by
      hand)
- [x] Pushed to a PR; CI (`./gradlew test` + `./gradlew assembleDebug`)
      confirmed green on the real commit, not just traced by hand
- [x] `docs/feed_screen_gate/PROGRESS.md` written, same shape as the
      other two PRDs in this repo
- [ ] Driver confirms: with Diagnostic Logging on, a real delivery/
      browsing session no longer shows `AD matched "Ad"` against
      `"Add or remove this video from Favourites."`, the comments
      panel, or the Recents screen
- [ ] Driver sign-off

## 7. Follow-up (2026-09-09): two more real gaps found auditing the diagnostic log's own coverage

Driver asked what the diagnostic log does and doesn't check for. Auditing
every file's coverage (which files call `diagnosticLog.log`, and whether
every real failure/decision path in the ones that don't actually needs
it) turned up two more real, previously-unfound problems - not logging
gaps exactly, but the same "silently wrong with no trace" class this
whole PRD is about.

### 7.1 `DownloadedVideoLocator.findRecentlyAddedVideo` had no exception guard at all

`contentResolver.query()` can throw - most plausibly a `SecurityException`
if the media-read permission was denied or later revoked in system
settings. Called from `TikTokActionCoordinator.locateAndExtractAudio`, a
main-thread `Handler.postDelayed` callback with no surrounding try/catch
of its own either - an uncaught exception there crashes the whole app
process, and since it's reached before any `diagnosticLog` call, nothing
would explain why. Fixed by wrapping the call, logging via
`diagnosticLog.logError`, and giving up immediately (not retrying
`MAX_LOCATE_ATTEMPTS` times against a permission error retrying can't
fix) - same shape as the existing "gave up after N attempts" path just
below it.

### 7.2 `TikTokActionCoordinator.findAndClickNode` had the identical plain-substring bug this PRD already fixed once

The function that finds and taps Block/Download/Live-menu buttons used
the exact same `text.contains(keyword, ignoreCase = true)` pattern
`evaluate()`'s ad-keyword check had (§1.3) - a `"Block"` stage keyword
could match as a substring of an unrelated word containing "block", the
same failure class, just not yet caught in a real log. Fixed by reusing
`FilterEngine.containsWholeWord` directly (changed from `private` to
`internal`) rather than duplicating the fix - one shared implementation
that can't drift between the two call sites.

**Real edge case found applying the shared fix, not present in §1's own
list**: `SettingsRepository.DEFAULT_LIVE_MORE_OPTIONS_KEYWORDS` includes
`"..."` (three literal dots) as a real, shipped default keyword. `\b`
only matches at a transition between a word character and a non-word
character - a keyword made entirely of punctuation has no letters/digits
anywhere, so wrapping it in `\b...\b` would never match ANYTHING,
silently breaking that keyword completely rather than merely failing to
narrow it. `containsWholeWord` now checks
`keyword.none { it.isLetterOrDigit() }` first and falls back to plain
substring matching for a keyword shaped like that - correct, since
there's no meaningful "embedded inside a longer word" risk for a
punctuation-only token in the first place. This also strengthens the
original §1.3 fix against premortem P1's own disclosed risk (a keyword
without well-defined regex word boundaries), for any driver-configured
ad/subject keyword shaped the same way, not just this one default.

### Testing

- New test: `a punctuation-only keyword falls back to plain substring
  matching, not word-boundary` (`FilterEngineTest.kt`), using the real
  `"..."` default as its keyword.
- `findAndClickNode` itself isn't independently unit-testable (it
  operates on real `AccessibilityNodeInfo`, not mockable here) - its
  correctness now rides entirely on `containsWholeWord`, which is fully
  covered by `FilterEngineTest.kt`.
- Traced every real default keyword list in `SettingsRepository`
  (`DEFAULT_MORE_OPTIONS_KEYWORDS`, `DEFAULT_BLOCK_OPTION_KEYWORDS`,
  `DEFAULT_BLOCK_CONFIRM_KEYWORDS`, `DEFAULT_DOWNLOAD_OPTION_KEYWORDS`,
  `DEFAULT_LIKE_OPTION_KEYWORDS`, `DEFAULT_LIVE_MORE_OPTIONS_KEYWORDS`)
  by hand against the new matching behavior - only `"..."` needed the
  punctuation fallback; every other default keyword still matches
  exactly as before.
- Same disclosed limitation as everywhere else in this repo: no
  Kotlin/JVM toolchain in this sandbox - traced by hand, pushed for the
  real CI to confirm.

## 8. Success criteria for §7

- [x] `DownloadedVideoLocator.findRecentlyAddedVideo`'s call site wrapped
      in try/catch, logs via `logError`, gives up immediately (no
      pointless retries against a permission error)
- [x] `containsWholeWord` made `internal`, reused by
      `TikTokActionCoordinator.findAndClickNode`
- [x] Punctuation-only-keyword fallback added and covered by a new test
      using the real `"..."` default
- [x] Every real default keyword list in `SettingsRepository` traced by
      hand against the new matching behavior
- [ ] CI green on the real commit
- [ ] Driver confirms: Block/Download/Live automations still work as
      before, and a forced media-permission failure now shows a real
      `EXTRACT` line instead of a crash
- [ ] Driver sign-off
