# Progress log — hard-stop escalation on runaway auto-skip

## Investigation + PRD (2026-08-30)

Confirmed `main` does not include the circuit breaker (PR #1, commit
`b96d2c0`) - still open/unmerged at the time of the driver's "still auto
scrolling" report, so the installed app almost certainly predates it
entirely. Also an honest self-critique: even with the fix, it only pauses
and resumes - a driver asking for "stopped" got "throttled to bursts."

Wrote `docs/auto_scroll_hard_stop/PRD.md` and `RALPH_PROMPT.md`.

## Premortem (2026-08-30)

Added §3a. Found and fixed a real design gap in the original draft: §3.1
only proposed disabling Skip ads + Skip blocked creators, but
`FilterEngine.evaluate` can also return a `REPEAT_VIEW` decision - if that
turned out to be the actual recurring cause, the hard stop would have
fired and disabled the wrong two toggles, leaving it silently ineffective.
Updated §3.1/§6 to disable all three. Also flagged (not fixed - a bigger
design question): the escalation counter is in-memory, so an OS process
kill between trips would silently reset it, same class of issue as
`dasher-monitor-`'s `docs/watchdog_reliability/` findings this session.

## Implementation (2026-08-30)

New `filter/HardStopGuard.kt` (`TripHistoryState` + `HardStopGuard.recordTrip`
- pure, no Android dependency, same pattern as `SkipStreakGuard`): tracks
circuit-breaker trip timestamps within a rolling window, prunes ones older
than the window, and reports when the count in-window reaches a threshold.

Wired into `TikTokFilterService.kt`:
- `tripHistoryState` field added.
- Every time the existing circuit breaker (`SkipStreakGuard`) trips, also
  call `HardStopGuard.recordTrip`.
- If it reports a hard stop (`MAX_TRIPS_IN_ESCALATION_WINDOW = 3` within
  `ESCALATION_WINDOW_MILLIS = 5 minutes`, both UNCONFIRMED per PRD §5.1):
  set `isAdSkipEnabled`, `isBlockedCreatorSkipEnabled`, and
  `isRepeatViewSkipEnabled` all to `false` via `SettingsRepository` (real,
  persisted settings, not an in-memory pause), and log a detailed
  Activity-log warning explaining what happened, that it's a manual
  re-enable in Filters, and what diagnostic evidence would actually help
  find the root cause.

Wrote `HardStopGuardTest.kt` (4 tests, matching `SkipStreakGuardTest.kt`'s
conventions): doesn't hard-stop below threshold, hard-stops on the trip
that reaches it, trips spread past the window don't accumulate, and a
caller-reset history after a hard-stop starts fresh.

## Verification (2026-08-30)

Same disclosed limitation as this repo's other PRDs: no Kotlin/JVM
toolchain in this sandbox, so `HardStopGuardTest` couldn't be run directly
here - traced through by hand against the pure `recordTrip` logic instead.
Pushed to PR #1's branch for the real CI
(`.github/workflows/android-build.yml`) to execute it for real.

**Confirmed green**: both `build` check runs on commit `7d1c911`
completed with `conclusion: success` (run
https://github.com/ddann74/tiktok-feed-filter/actions/runs/33395411480,
completed 2026-08-31T13:10:49Z) - `./gradlew test` (which includes
`HardStopGuardTest`, `SkipStreakGuardTest`, `FilterEngineTest`, and
`ActionSequenceTest`) and `./gradlew assembleDebug` both passed. PR #1's
`mergeable_state` is `clean`.

Remaining PRD §6 box: user sign-off.
