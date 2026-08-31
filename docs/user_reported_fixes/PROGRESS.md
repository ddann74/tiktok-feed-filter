# Progress log — user-reported reliability and navigation fixes

## Investigation (2026-08-30)

Read the full git history (21 commits) and every source file relevant to
each of the four reported items before changing anything. Findings:

- **Diagnostic logging default**: already `true` in
  `SettingsRepository.isDiagnosticLoggingEnabled` since commit `e0563aa`
  (the current HEAD at investigation time) - only `DiagnosticLog`'s own
  doc comment was stale, still claiming "default off."
- **Auto-scroll runaway**: the exact symptom already has one confirmed,
  fixed root cause in history (`93a5ed0`, repeat-view fingerprint
  collision) - confirmed still fixed in current `FilterEngine`. No
  diagnostic log from the actual new incident was available to confirm a
  different cause, so rather than guess at a keyword-matching change, added
  a circuit breaker that caps runaway skipping regardless of cause.
- **Navigation**: confirmed `activity_main.xml` is one 1019-line
  `ScrollView` with 12+ sections and no way to jump between them.
- **"Pre-screened ads" feature**: searched full git history and every
  current file - found no matching feature ever existed. Not implemented;
  flagged as an open question in PRD.md §5 rather than guessed at.

Wrote `docs/user_reported_fixes/PRD.md` and `RALPH_PROMPT.md`.

## Implementation (2026-08-30)

**1. Diagnostic logging (PRD §3.1's predecessor / §1.1)**: fixed
`DiagnosticLog.kt`'s stale class-doc comment to say "default ON" and cite
why. No behavior change - the setting was already correct.

**2. Circuit breaker (PRD §3.1)**: new `filter/SkipStreakGuard.kt`
(`SkipStreakState` + `SkipStreakGuard.recordSkip` - pure, no Android
dependency, same pattern as `FilterEngine`). Wired into
`TikTokFilterService.kt`:
- `skipStreakState`/`circuitBreakerTrippedUntilMillis` fields added.
- Reset the streak on every genuine (non-skipped) view.
- Record every skip about to be performed; if it trips
  (`MAX_CONSECUTIVE_SKIPS = 8` within `SKIP_STREAK_WINDOW_MILLIS = 15_000L`),
  skip the gesture, pause auto-skip for `CIRCUIT_BREAKER_PAUSE_MILLIS =
  30_000L`, and log a clear warning to both `StatsRepository.recordEvent`
  (user-visible Activity log) and `DiagnosticLog`.
- A new pause check alongside the existing `COOLDOWN_MILLIS` check, so
  auto-skip evaluation is skipped entirely while tripped.

**3. Live Streams keyword UI (existing `docs/PRD.md` §3.1, P1)**: added the
missing `liveIndicatorKeywords` list to `activity_main.xml` (new "Live
Indicator Keywords" sub-section under Live Streams, mirroring the existing
9-list input/add-button/remove pattern) and `MainActivity.kt`
(`renderLiveIndicatorKeywords`, add-button listener, wired into
`renderAllLists()`). This closes the P1 gap the existing hardening PRD had
already identified but not yet implemented.

**4. Quick-jump navigation bar (PRD §3.3)**: restructured
`activity_main.xml`'s root from a single `ScrollView` to a `LinearLayout`
containing a fixed `HorizontalScrollView` of 9 section chips (`Filters`,
`Blocked`, `Ad Keywords`, `Subject Boost`, `Packages`, `Block/Download`,
`Live`, `Activity`, `Diagnostics`) followed by the original content, now in
its own `id=mainScrollView` `ScrollView` taking the remaining height. Added
`id`s to each of the 9 section headers. Added a `QuickJumpChip` style to
`themes.xml`. Wired `MainActivity.setupQuickJumpNav()` to scroll to each
section on tap.

## Verification (2026-08-30)

**XML well-formedness** (`xml.etree.ElementTree.parse`) on both changed XML
files. **Caught and fixed one real mistake this way**: the first attempt at
restructuring `activity_main.xml`'s ending accidentally dropped the closing
`</LinearLayout>` tag for the main content container while adding the new
outer `LinearLayout`/`ScrollView` wrapper - confirmed via a custom SAX
handler that traced exactly which element was left unclosed
(`content LinearLayout`, opened at the original line 45) before fixing it.
Re-ran `ElementTree.parse` after the fix - both files now well-formed.

**id cross-check**: every `id` referenced in `MainActivity.kt`'s new code
(`navChip*`, `section*`, `mainScrollView`, `liveIndicatorKeyword*`)
confirmed to exist exactly once in `activity_main.xml` via `grep -c`.

**Unit tests for `SkipStreakGuard`**: wrote `SkipStreakGuardTest.kt`
(4 tests: doesn't trip below threshold, trips on the skip that exceeds it,
a streak spread past the window resets instead of accumulating, a
caller-reset streak after tripping starts fresh) matching
`FilterEngineTest.kt`'s existing conventions. **Not executed** - this
environment has no `kotlinc`/JVM-Kotlin toolchain reachable (confirmed:
`kotlinc` not on PATH, and this repo's own Android Gradle build needs an
Android SDK this environment doesn't have network access to, per the
project's own already-disclosed limitation in `docs/PRD.md` §4). Traced
through by hand against the pure `recordSkip` logic instead; would run via
`./gradlew test` in Android Studio or CI.

**Not done**: item #4 ("pre-screened ads") - no matching feature found in
history, flagged as an open question (PRD.md §5) rather than guessed at.

## CI added (2026-08-30, at driver's request)

Requested explicitly after the PR was opened, to close the "tests not
executed in this environment" gap from the section above.

**Found and fixed a separate, pre-existing bug first**: this repo's Gradle
wrapper was incomplete - only `gradle-wrapper.properties` was ever
committed (confirmed via `git ls-files | grep wrapper`); `gradlew`,
`gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` did not exist at
all. `./gradlew` cannot run without them, in CI or locally. Fixed the same
way `dasher-monitor-`'s missing `gradle-wrapper.jar` was fixed earlier this
session: generated a Gradle 8.7 wrapper (matching this repo's own
`gradle-wrapper.properties`) via system Gradle in an isolated empty
directory (avoids evaluating this project's own `build.gradle.kts`, which
needs Android SDK plugins unavailable here), then verified the copied jar
actually works: `java -classpath gradle/wrapper/gradle-wrapper.jar
org.gradle.wrapper.GradleWrapperMain --version` correctly downloaded and
reported "Gradle 8.7" before committing anything.

Added `.github/workflows/android-build.yml`: JDK 17 (AGP 8.5.2 requires
it) + `android-actions/setup-android@v3`, `./gradlew test` (runs
`FilterEngineTest`, `ActionSequenceTest`, and the new
`SkipStreakGuardTest` for real, for the first time) before `./gradlew
assembleDebug`, uploading the debug APK and JUnit XML results as
artifacts. Validated the workflow YAML with `yaml.safe_load` before
committing.

This directly closes PRD.md §4's disclosed "not executed" gap for
`SkipStreakGuardTest` - the tests still can't run in this sandbox, but now
run for real on every push via CI.

Final PRD §6 boxes remaining: item #4 resolution, user sign-off.
