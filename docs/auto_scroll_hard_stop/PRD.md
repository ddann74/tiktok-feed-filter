# PRD: Make the runaway auto-skip actually stop, not just pause

Status: DRAFT - awaiting sign-off before implementation begins.
Scope: this one report only. Not a general codebase pass.

## 0. What this is / isn't

The driver reported "the app is still auto scrolling, I want it stopped"
AFTER `docs/user_reported_fixes/PRD.md`'s circuit breaker
(`SkipStreakGuard`) was written and pushed. This PRD does not assume that
fix was wrong - it investigates why the symptom could still be happening,
and designs something stronger where the previous pass deliberately fell
short (see §1.2's honest self-critique).

## 1. Why (investigation, 2026-08-30)

### 1.1 Most likely explanation: the fix isn't on the device yet

Checked `main` vs. the fix branch: `git log --oneline` on `main` does
**not** include commit `b96d2c0` (the circuit breaker) - it's only on
`claude/new-chat-sqglb4`, in PR #1, **still open and unmerged** at the time
of this report. Unless the driver specifically built and sideloaded the PR
branch (nothing in this conversation indicates that - every APK/clone
request so far has been for a general checkout, not that specific commit),
**the installed app almost certainly predates the circuit breaker
entirely**, and is running the exact same code that had no runaway
protection at all.

**This PRD's first checklist item is confirming this**, not skipped as
"probably fine" - if it turns out the driver IS already running a build
with `SkipStreakGuard`, that changes what needs fixing (see §1.2).

### 1.2 Honest self-critique: even with the fix, "stopped" isn't what was built

Re-reading `docs/user_reported_fixes/PRD.md` §3a (premortem, P1) - written
at the time, not added after the fact: "the circuit breaker mitigates, but
doesn't diagnose... the app will now pause for 30s and log a warning every
~8 skips instead of running away forever - a real improvement - but the
underlying over-matching is still there, still burning through real videos
in bursts... If the driver reports 'it still happens, just in shorter
bursts now,' that's this - not a failed fix, an incomplete one."

**The driver asked for "stopped," and what was built was "throttled to
8-skip bursts with a 30s pause in between."** Those are different things,
and from inside a burst of 8 rapid skips, it can look and feel exactly like
"still auto scrolling" - the driver's report is consistent with the
circuit breaker working exactly as designed and that still not being
enough.

### 1.3 The actual root cause is still unconfirmed

No diagnostic log from an actual incident has been provided yet. The
README's own confirmed finding (`"Ad starts in"` never matching a
genuinely-current video, only preloaded ones) remains the best-evidenced
hypothesis for an over-matching Ad Keyword, but it's still a hypothesis,
not a confirmed cause for THIS driver's device. Per this repo's own
established discipline (every keyword-list fix here has come from a real
diagnostic log, never a guess), this PRD does not change
`DEFAULT_AD_KEYWORDS` or matching semantics without one.

## 2. Definition of "done" for this task

- [ ] Confirmed whether the driver's installed build includes the circuit
      breaker (PR #1 / commit `b96d2c0`) or predates it - changes which fix
      actually matters here.
- [ ] A genuine hard stop exists: if the circuit breaker's mitigation
      (pause + resume) trips repeatedly back-to-back, auto-skip actually
      turns itself off (not just pauses again) until the driver manually
      re-enables it - "stopped" in the sense the driver actually asked for.
- [ ] The hard-stop event is impossible to miss - not a line in a log the
      driver has to go looking for, but something that's obviously
      different from normal operation.
- [ ] A path to the actual root cause exists that doesn't require guessing:
      the hard-stop's own trigger conditions double as instructions for
      what diagnostic evidence to capture next.
- [ ] No change to `DEFAULT_AD_KEYWORDS`, ad-keyword matching semantics, or
      any other heuristic's defaults - still no evidence to justify one.

Non-goals:
- Guessing at and changing the ad-keyword matching logic (substring vs.
  word-boundary) - still blocked on real evidence, same as
  `docs/user_reported_fixes/PRD.md`'s own guardrail.
- Re-tuning `MAX_CONSECUTIVE_SKIPS`/`SKIP_STREAK_WINDOW_MILLIS`'s exact
  numbers without evidence they're wrong - the problem being fixed here is
  "the mitigation lets the pattern repeat forever," not "8 is the wrong
  number."

## 3. Design

### 3.1 Escalating circuit breaker: pause once, then hard-stop

`TikTokFilterService`/`SkipStreakGuard` currently: trip once -> pause
`CIRCUIT_BREAKER_PAUSE_MILLIS` (30s) -> streak resets -> repeat forever if
the underlying cause is still there. Add one more state: track how many
times the breaker has tripped within a longer rolling window (e.g. 3 trips
within 5 minutes - UNCONFIRMED reasonable threshold, same honesty status
as every other one in this app). On the Nth trip in that window, instead
of just pausing again:

- Turn **Skip ads**, **Skip blocked creators**, AND **Repeat-view skip**
  off entirely (`SettingsRepository.isAdSkipEnabled` /
  `isBlockedCreatorSkipEnabled` / `isRepeatViewSkipEnabled` set to `false`,
  not just an in-memory pause) - a real stop, survives the service
  restarting, and matches exactly what "stopped" means to a driver
  checking Setup afterward. All three, not just the first two - see §3a-P2:
  a streak can be driven by any `SkipReason` `FilterEngine.evaluate` can
  return, and disabling only two of the three toggles would leave the
  hard stop silently ineffective if the third is what's actually recurring.
- Log a maximally visible warning to the Activity log (not buried) naming
  the fact that auto-skip was disabled automatically, why, and that
  turning it back on is a manual step in **Filters**.
- Do NOT auto-disable Subject Boost or the Block/Download automations -
  neither can produce a `SkipDecision` at all (Subject Boost only likes,
  never skips - see its own doc in `SettingsRepository`), so neither is
  implicated in this failure mode.

### 3.2 Making the trigger double as the diagnostic ask

The hard-stop's Activity log line should explicitly tell the driver what
to do next: turn on/confirm Diagnostic Logging is on (already default),
reproduce the issue if it happens again, and share the log - the same
"tell them what evidence would actually fix this" pattern used everywhere
else in this repo's PRDs, rather than shipping a guess.

## 3a. Premortem (2026-08-30): assume this pass fails again

- **P1 — the escalation state is in-memory, so an OS process kill silently
  defeats the whole design.** `SkipStreakGuard`'s streak state and the new
  trip-counting-within-a-window state both live as fields on the running
  `TikTokFilterService` instance. If Android (or an aggressive OEM battery
  manager - a real, confirmed issue in the sibling `dasher-monitor-` app's
  `docs/watchdog_reliability/` investigation this same session) kills and
  restarts the accessibility service between trips, the trip counter resets
  to zero. A driver whose device does this could see the pause-then-resume
  cycle repeat indefinitely and NEVER reach the hard stop - the exact
  failure this PRD exists to close, silently reopened by something outside
  this PRD's own code. Not fixed here (would need persisting trip count to
  `SharedPreferences`, a real design question, not a one-line fix) -
  flagged as a real risk, not solved.
- **P2 — disabling only Skip ads + Skip blocked creators doesn't cover
  every path that can trigger the circuit breaker.** `FilterEngine.evaluate`
  can also return a `REPEAT_VIEW` decision (if the driver has Repeat-View
  Skip turned on - off by default, but not guaranteed off). The current
  §3.1 design hard-codes disabling only the ad/blocked-creator toggles; if
  the actual runaway is being driven by repeat-view skip instead, the hard
  stop would fire, disable the wrong two toggles, and the runaway would
  continue completely unaffected. Needs the hard-stop to either track which
  `SkipReason` is actually recurring and disable the matching toggle(s), or
  - simpler, and probably safer given "make it actually stop" is the goal
  - disable all three skip toggles on trip, not just two. Recommend the
  latter in §3 unless the driver wants finer granularity (see open
  questions).
- **P3 — 24+ real skips can still happen before the hard stop ever fires.**
  3 trips × 8 skips/trip = up to 24 rapid skips (roughly 2-3 minutes of
  real content) before the escalation kicks in. If the underlying cause is
  aggressive (e.g. a one-character keyword matching nearly everything),
  "stopped" might still feel too slow even once this ships - the
  thresholds in §5.1 may need to come down, not just exist.
- **P4 — auto-disabling Skip ads is itself a surprising, silent-feeling
  side effect if the driver doesn't read the Activity log.** A driver who
  doesn't check Activity could reasonably conclude days later that "ad
  skipping just stopped working" and file that as a NEW, separate bug
  report, not realizing it was this safety mechanism protecting them
  earlier. The Activity log line (§3.2) has to be genuinely unmissable, not
  just present - worth considering a one-time Toast or notification in
  addition to the log line if driver feedback shows the log alone isn't
  enough.
- **P5 — this still doesn't fix anything if the real cause turns out to be
  outside ad/blocked-creator matching entirely** (e.g. a genuinely new
  video-transition edge case in `TikTokFilterService` itself, unrelated to
  any keyword list). §3.1's hard stop would still correctly stop the
  symptom either way (once P2 is addressed), but the PRD's own §1.3 stands:
  the actual mechanism stays unconfirmed without a real diagnostic log.

## 4. Testing / verification approach

Same disclosed limitation as every other PRD here: no Android SDK/
emulator/device in this environment. The escalation-tracking logic
(counting trips within a window) is the same shape as `SkipStreakGuard`
itself and can be extracted the same way - pure, unit-tested, verified via
`./gradlew test` in CI (now that CI actually exists and runs it - see
`docs/user_reported_fixes/PRD.md` §3.4).

## 5. Open questions

1. **Trip-escalation thresholds** (3 trips / 5 minutes) are an
   UNCONFIRMED guess, same as `MAX_CONSECUTIVE_SKIPS` was. Not blocking -
   flagged so the driver can ask for a different number if 3-in-5-minutes
   turns out too trigger-happy or too slow in practice.
2. **Should this actually wait for a real diagnostic log before writing
   any more code at all?** The driver asked for a PRD/Ralph loop directly,
   so this PRD is written - but the honest position is that §3.1 is a
   stronger safety net, not a fix for whatever is actually over-matching.
   If the driver can get one diagnostic log from an actual incident, that
   would let the NEXT PRD fix the real cause instead of hardening around
   it again.

## 6. Success criteria (implementation-phase checklist)

- [ ] Confirmed/documented whether the driver's build includes the
      existing circuit breaker or predates it
- [ ] Escalating hard-stop implemented: N trips within a window disables
      Skip ads + Skip blocked creators + Repeat-view skip (not just
      another pause, and not just two of the three possible causes - see
      §3a-P2)
- [ ] Hard-stop logs a clear, unmissable Activity log entry naming what
      happened, why, and the manual re-enable step
- [ ] Hard-stop logic kept pure/unit-testable, mirroring `SkipStreakGuard`
- [ ] New unit tests written AND executed via CI (`./gradlew test`) - not
      just written, per this repo's now-available verification path
- [ ] `DEFAULT_AD_KEYWORDS`/matching semantics confirmed unchanged by diff
      review
- [ ] User sign-off
