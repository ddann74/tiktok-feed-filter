# PRD: User-reported reliability and navigation fixes

Status: IMPLEMENTED (2026-08-30) - see PROGRESS.md for what was actually done
and verified, and open question below for the one item held back for
clarification.
Scope: four specific, user-reported items. Not a general codebase pass.

## 0. What this is / isn't

The driver reported four things in one message:

1. "log all diagnostics by default"
2. "stop the auto scroll it is out of control"
3. "i cant navigate the app efficiently"
4. "there was a feature that pre-screened ads so they didn't show up can you
   put this back in"

This PRD investigates each against the real code (not assumption), fixes
what's concretely fixable, and is explicit about the one item (#4) that
couldn't be matched to anything that actually exists in this app's history.

## 1. Investigation (code-verified, 2026-08-30)

### 1.1 "log all diagnostics by default"

**Already true in the code as of the most recent commit before this PRD**
(`e0563aa`, "Default diagnostic logging to on instead of off").
`SettingsRepository.isDiagnosticLoggingEnabled` (L54-56) defaults to `true`.
The only real gap found: `DiagnosticLog`'s own class doc comment (L18) still
said "(default off)" - stale, left over from before that commit. Fixed as a
one-line doc correction; no behavior change, since the code itself was
already correct. **If diagnostics still aren't showing up on a real
device, that means the running APK predates commit `e0563aa` and needs a
rebuild from current `main`/this branch - not a code gap.**

### 1.2 "stop the auto scroll it is out of control"

This exact symptom was already hit and fixed once before, in commit
`93a5ed0` ("Fix repeat-view fingerprint collision causing continuous
auto-scroll") - confirmed present and correct in the current
`FilterEngine.videoFingerprint` (refuses to fingerprint a video with no
real caption-proxy text, rather than collapsing to a shared/empty
fingerprint). **If this is a recurrence of that exact bug, it means the
running APK predates `93a5ed0` and needs a rebuild.**

No diagnostic log from the actual incident was available to confirm a
*different*, not-yet-fixed cause. Rather than guess at one keyword-list
change (e.g. tightening ad-keyword matching, which would risk breaking
currently-working matches for someone else's configured list, entirely
speculative without log evidence), this PRD adds a **circuit breaker**:
regardless of root cause, the skip gesture should never be able to fire
indefinitely without at least pausing and telling the user - the same
"fail toward doing nothing, and say so" pattern the Download in-flight
lock (`TikTokActionCoordinator`) already uses for an analogous problem.

### 1.3 "i cant navigate the app efficiently"

Confirmed real: `activity_main.xml` is a single `ScrollView` with 12+
sections (Filters, Blocked Creators, Ad Keywords, Subject Boost, Target App
Packages, Real TikTok Integration [with 4 sub-lists], Live Streams [with 2
sub-lists], Activity, Diagnostics) stacked vertically with no way to jump
between them - reaching Diagnostics (the section actually needed when
something's wrong) means scrolling past everything else, every time.

### 1.4 "pre-screened ads so they didn't show up"

**Investigated via full git history (`git log --oneline`, 21 commits) and
every current file - found nothing matching this description.** This app
has never had a feature that visually hides/covers an ad before it renders;
the existing "Skip ads" mechanism (`isAdSkipEnabled`, on by default, plus
the editable **Ad Keywords** list) detects an ad already on screen and
swipes past it - which does mean the driver doesn't end up watching it,
but isn't a "pre-screen" in the sense of never rendering at all (there's a
brief render-then-skip window, same as every skip in this app).

**Not implemented pending clarification** (see Open questions) - closest
guess is that "Skip ads" and/or the Ad Keywords list got turned off/cleared
on the actual device, in which case turning it back on in **Filters**
already restores exactly this behavior with no code change needed. Building
something else on a guess risked wasted effort in either direction.

## 2. Definition of "done" for this pass

- [x] Diagnostic logging confirmed on by default in code; stale doc comment
      fixed.
- [x] A circuit breaker added so auto-skip can never run indefinitely
      without pausing and telling the user, regardless of cause.
- [x] A fixed quick-jump nav bar added so every major section is one tap
      away, not a long scroll.
- [ ] Item #4 - blocked on clarification (see Open questions). Not
      implemented as speculative work against an unconfirmed feature.
- [x] CI added so `SkipStreakGuardTest` (and the pre-existing
      `FilterEngineTest`/`ActionSequenceTest`) actually run somewhere,
      closing the "not executed in this environment" gap in §4 below.

## 3. Design

### 3.1 Circuit breaker (`SkipStreakGuard`)

New pure Kotlin file `filter/SkipStreakGuard.kt` (`SkipStreakState` +
`SkipStreakGuard.recordSkip`) - same reasoning `FilterEngine`/
`ActionSequence` are kept pure and separately unit-tested: counts
consecutive skips within a rolling window, resets if the window elapses,
and reports when the count crosses a threshold. Wired into
`TikTokFilterService`:

- On every genuine (non-skipped) view: reset the streak - real evidence
  browsing is progressing normally.
- On every skip about to be performed: record it; if the streak trips
  (`MAX_CONSECUTIVE_SKIPS = 8` within `SKIP_STREAK_WINDOW_MILLIS = 15s`,
  both UNCONFIRMED reasonable-sounding thresholds, same honesty status as
  every other threshold in this app), don't perform that skip either -
  instead set `circuitBreakerTrippedUntilMillis` (`CIRCUIT_BREAKER_PAUSE_MILLIS
  = 30s` pause), log a clear warning to BOTH the Activity log
  (`StatsRepository.recordEvent` - user-visible in the app, not buried in
  Diagnostic Log only) and the Diagnostic Log, and reset the streak so the
  pause itself doesn't count against whatever comes after it.
- While tripped, auto-skip evaluation is skipped entirely (same shape as
  the existing `COOLDOWN_MILLIS` check, just a longer window and a
  one-time warning instead of every event).

### 3.2 Live Streams keyword UI

Implements the existing `docs/PRD.md` §3.1 (P1) exactly as specified there:
added the missing `liveIndicatorKeywords` list UI (input, add button,
rendered list with per-item remove) to `activity_main.xml` and
`MainActivity.kt`, mirroring the pattern already used for the other 9
editable lists. Folded into this PRD rather than duplicated as a separate
implementation pass, since it directly serves both the navigation fix
(one more properly-organized section) and is the only actionable lever for
the README's own worst documented bug (`isLiveStream` 99% false-positive
rate).

### 3.3 Quick-jump navigation bar

Restructured `activity_main.xml`'s root from a single `ScrollView` into a
`LinearLayout` containing a fixed (non-scrolling) `HorizontalScrollView` of
section chips, followed by the original scrolling content now in its own
`ScrollView` (`id=mainScrollView`) that takes the remaining height
(`layout_weight=1`). Each of the 9 major section headers got an `id`;
`MainActivity.setupQuickJumpNav()` wires each chip to
`mainScrollView.smoothScrollTo(0, section.top)`. The nav bar stays visible
while the content below it scrolls, so Diagnostics (or any section) is
always one tap away.

## 3a. Premortem (2026-08-30): assume this pass fails again

- **P1 — the circuit breaker mitigates, but doesn't diagnose, the
  auto-scroll report.** If the real cause is an over-broad Ad Keyword (a
  short/generic entry substring-matching normal captions) or a genuinely
  new edge case, the app will now pause for 30s and log a warning every
  ~8 skips instead of running away forever - a real improvement - but the
  underlying over-matching is still there, still burning through real
  videos in bursts, and still needs a diagnostic log to actually fix at
  the source. If the driver reports "it still happens, just in shorter
  bursts now," that's this - not a failed fix, an incomplete one, exactly
  as scoped in §1.2.
- **P2 — the 8-skips/15s/30s-pause thresholds are unconfirmed against a
  real device**, same honesty status as every other threshold in this
  app (`DEFAULT_SPEED_LIMIT_KMH`-style guess, not measured). A genuinely
  ad-heavy stretch of real TikTok content (several back-to-back ads, which
  does happen) could trip the breaker on legitimate skips, pausing
  auto-skip for 30s while ads play through unfiltered - the opposite of
  what was asked for, in a false-positive case. If this happens often in
  practice, the fix is loosening `MAX_CONSECUTIVE_SKIPS`/
  `SKIP_STREAK_WINDOW_MILLIS`, not architecture.
- **P3 — item #4 remains unresolved and un-investigated beyond the git
  history search.** If the driver's actual device shows ads getting
  through with Skip Ads confirmed on (already reported - see the
  conversation, not yet turned into a diagnostic-log-backed fix), the
  most likely cause per the README's own confirmed finding is
  `"Ad starts in"` never matching a genuinely-current video - but this is
  still a hypothesis, not confirmed against this driver's actual log.
- **P4 — the quick-jump nav and Live Streams keyword UI are both
  UI-only changes with zero on-device verification**, same disclosed
  limitation as every other UI change in this repo's PRDs (no Android
  SDK/emulator/device reachable from this environment). A layout issue
  (chip text truncation, `smoothScrollTo` landing slightly off due to
  padding, `liveIndicatorKeywordsContainer` ID typo) would only surface
  once actually run in Android Studio or on a device.

### 3.4 CI (`.github/workflows/android-build.yml`)

Added after the fact, at the driver's request, once this environment's own
inability to run the new unit tests became a real gap rather than a
disclosed limitation. Also found and fixed a **separate, pre-existing bug**
while setting this up: this repo's Gradle wrapper was incomplete -
`gradle-wrapper.properties` existed, but `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar` did not (confirmed via `git ls-files` -
never committed at all, same class of gap `dasher-monitor-` had with just
the jar). Without these, `./gradlew` cannot run at all, in CI or locally -
fixed by generating a matching Gradle 8.7 wrapper (via system Gradle in an
isolated directory, to avoid evaluating this project's own
Android-SDK-dependent `build.gradle.kts`) and verifying the jar actually
works (`java -classpath gradle-wrapper.jar
org.gradle.wrapper.GradleWrapperMain --version` reports Gradle 8.7
correctly) before committing it.

The workflow itself: JDK 17 (required by AGP 8.5.2, confirmed in the root
`build.gradle.kts`) + `android-actions/setup-android@v3`, then
`./gradlew test` (runs `FilterEngineTest`, `ActionSequenceTest`, and the
new `SkipStreakGuardTest`) before `./gradlew assembleDebug`, uploading both
the debug APK and the JUnit XML results as workflow artifacts. No API keys
or `local.properties` needed - confirmed via the README's own Privacy
section that this app makes no network calls at all.

## 4. Testing / verification approach

Same disclosed limitation as this repo's own `docs/PRD.md` §4: no Android
SDK, emulator, or TikTok install available in this environment.

- **3.1 (circuit breaker)**: the pure decision logic (`SkipStreakGuard`) is
  directly unit-testable and has real tests (see PROGRESS.md) - this
  environment has no Kotlin/JVM toolchain reachable (confirmed: no
  `kotlinc` on PATH), so the tests couldn't run here directly. **Closed by
  §3.4 below**: a GitHub Actions workflow now runs `./gradlew test` on
  every push, so the tests actually execute, just not in this sandbox.
- **3.2/3.3 (XML/UI)**: not unit-testable without instrumentation, same as
  every other UI-only item in `docs/PRD.md`. Verified instead by: (a)
  `xml.etree.ElementTree` parsing both changed XML files to confirm
  well-formedness (caught and fixed one real mistake - see PROGRESS.md),
  and (b) manually cross-checking every `id` referenced in `MainActivity.kt`
  exists exactly once in `activity_main.xml`.

## 5. Open questions

1. **Item #4 (ad pre-screening)**: what exactly should this do?
   - Is "Skip ads" (or the Ad Keywords list) currently turned off/empty on
     the actual device - in which case turning it back on in **Filters**
     already does this, no code change needed?
   - Or is this a genuinely new feature request: visually hide/cover an ad
     the instant it's detected (before or during the skip swipe), rather
     than the current detect-then-swipe-past behavior?
   - Or something else entirely not covered by either guess above?

   Not implemented pending an answer - see PROGRESS.md.

## 6. Success criteria (implementation-phase checklist)

- [x] Diagnostic logging default confirmed correct; stale comment fixed
- [x] `SkipStreakGuard` circuit breaker implemented and wired into
      `TikTokFilterService`
- [x] Unit tests written for `SkipStreakGuard` (not executed in this
      environment - see §4)
- [x] `liveIndicatorKeywords` UI added (closes `docs/PRD.md` P1)
- [x] Quick-jump navigation bar added, one tap to every major section
- [x] Both changed XML files confirmed well-formed
      (`xml.etree.ElementTree`)
- [ ] Item #4 clarified and, if still wanted, implemented as its own
      follow-up
- [ ] User sign-off
