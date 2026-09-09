# PRD: Track every video by category (Ad / Post / Unidentified) and how long it was on screen

Status: APPROVED - driver answered ss5's open questions (2026-09-09),
implementation underway. See ss5 for the answers and ss1/ss2.1/ss3 for
how each one changed the design from the original DRAFT.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

Driver asked, after the diagnostic-log audit rounds (docs/feed_screen_gate/PRD.md
ss7-ss10), for something genuinely new rather than another silent-failure
fix: a record of what category every video fell into and how long it was
on screen. Four design questions were asked directly (`AskUserQuestion`)
before writing this PRD, per this repo's own established practice
(see docs/store_wait_timer/PRD.md's identical two-question precedent).
Answered:

1. **Category** = whether a video was an Ad, a Post (genuine, non-ad
   content), or Unidentified (couldn't tell what it even was) - driver's
   own words, not the topic/subject taxonomy (Sports/Comedy/etc.) this
   PRD's own first draft of the question assumed.
2. **Data shape** = per-video log entries, not just aggregate rollups.
3. **Where shown** = the existing Activity log (`StatsRepository`), not a
   new dedicated screen.
4. **Background-time gap** (accessibility events only fire while TikTok
   is foreground, so there's no direct "went to background" signal) =
   cap duration at a maximum per video rather than count unbounded time.

This PRD is the design + premortem pass for those four decisions, per
this repo's usual DRAFT -> premortem -> RALPH_PROMPT flow - not yet
implemented.

## 1. What "category" maps to in the existing code

**Updated 2026-09-09 per driver's ss5/P2 answer ("separate category"):**
`BLOCKED_CREATOR` and `REPEAT_VIEW` each get their own category rather
than folding into Post. Five categories total, each mapping 1:1 onto a
distinction this app already computes on every accessibility event -
just not as one named concept before now:

- **Ad** = `FilterEngine.evaluate(...)?.reason == SkipReason.AD`.
- **BlockedCreator** = `FilterEngine.evaluate(...)?.reason == SkipReason.BLOCKED_CREATOR`.
- **RepeatView** = `FilterEngine.evaluate(...)?.reason == SkipReason.REPEAT_VIEW`.
- **Unidentified** = `FilterEngine.videoIdentity(texts) == null` (see
  that function's own doc: null only when there's truly nothing to
  identify the video by - no creator handle, no usable caption). Takes
  priority over the three `SkipDecision`-based categories above only in
  the sense that those three already imply a non-null identity
  (`evaluate` itself reads from the same text block); this case is
  reached when there's no `SkipDecision` at all AND no identity.
- **Post** (the "genuine view" case) = everything else -
  `videoIdentity(texts) != null` and no `SkipDecision` fired.

`videoIdentity`, not `videoFingerprint`, is proposed as the ONE identity
this feature keys off - it's already the more robust of this file's two
identity functions (see its own doc: prefers the real creator handle,
falls back to a stabilized full-text signature), and is currently only
computed on the skip path (§1.3 of `docs/feed_screen_gate/PRD.md`'s own
line reference: `TikTokFilterService.kt` line ~251, inside the
`decision != null` branch). This PRD would need it computed
UNCONDITIONALLY, right after `isLive` (line ~190) - a new call, not
reusing the skip path's existing one, so the skip-dedup guard's own
behavior (§ dashes to `docs/skip_dedup_root_cause/PRD.md`) is completely
untouched.

## 2. The real conflict this scoping pass found: the 50-entry Activity log cap

`StatsRepository.MAX_LOG_ENTRIES = 50` - a newest-first, oldest-dropped
cap, persisted to `SharedPreferences`. Today the Activity log only grows
on a skip, a Block/Download event, or a Subject Boost like - genuinely
infrequent relative to how many videos actually pass by while scrolling.

**Logging one entry per Post (the majority of what's watched, by
definition) would flood this 50-entry cap within well under a minute of
normal scrolling**, pushing out the Ad-skip/Block/Download/circuit-
breaker entries that are the ENTIRE reason this log exists (its own doc
comment: "so a heuristic, best-effort filter like this one is
auditable"). A literal reading of "per-video entries in the Activity
log" would make the Activity log materially LESS useful for its
existing purpose, not more useful for this new one - a real regression,
not a hypothetical one.

### 2.1 Resolution (driver-confirmed 2026-09-09)

- **Ad, BlockedCreator, RepeatView**: no new entry for any of the
  three - each already produces its own `StatsRepository.recordSkip`
  line today (`"Ad skipped (matched \"...\")"`,
  `"Blocked creator skipped"`, `"Repeat view skipped"`). Enrich that
  EXISTING line with how long the video was on screen before the skip
  fired. Zero added volume for all three, not just Ad - the ss5/P2
  "separate category" answer is free here precisely because these
  three already had their own log line before this PRD existed; only
  the enrichment (duration) and the in-memory category bookkeeping are
  new.
- **Unidentified**: log every occurrence. Expected to be rare (most
  videos have SOME identifiable creator/caption) - low volume by
  construction, not an assumption this PRD takes on faith; §4's testing
  approach includes confirming this against the real diagnostic log
  already on hand.
- **Post**: do NOT log every single one - the volume driver. Only log a
  Post entry once its capped duration crosses a real "was this actually
  watched" threshold. Driver's ss5/P3 answer ("start with that"): ship
  with the proposed 3 seconds as the initial value rather than block
  implementation on calibrating it further - still UNCONFIRMED against
  a full real-world session, same honesty status as every threshold in
  this app, adjustable later if the driver reports it's wrong in either
  direction. A video watched for less than that produces no Activity
  entry at all, same as today.

This is a real, disclosed DEVIATION from a literal "log every video"
reading of the driver's own chosen answer (§0.2) - flagged prominently
here, not silently decided, specifically so it can be overridden. Two
named alternatives if this compromise isn't wanted:

- Raise `MAX_LOG_ENTRIES` substantially (e.g. 500) - simpler, no
  filtering logic, but `SharedPreferences` isn't designed for a large,
  frequently-rewritten string value, and old skip entries would still
  eventually scroll off faster than they do today, just later.
- Give this feature its OWN separate log/counter storage, not mixed
  into the general Activity feed - directly contradicts the driver's
  own "Activity log only" answer (§0.3), so not proposed as the default,
  but named here in case the volume tradeoff above isn't acceptable.

## 3. Design

New pure class `VideoWatchTracker` (no Android dependency, same
"testable without a device" pattern as `SkipStreakGuard`/`HardStopGuard`/
`ActionSequence` in this same repo):

```kotlin
enum class VideoCategory { AD, BLOCKED_CREATOR, REPEAT_VIEW, POST, UNIDENTIFIED }

data class FinishedWatch(val category: VideoCategory, val durationMillis: Long)

class VideoWatchTracker(private val maxDurationMillis: Long) {
    private var trackedIdentity: String? = null
    private var trackedCategory: VideoCategory? = null
    private var startedAtMillis: Long = 0L

    /** Call on EVERY accessibility event, first, before anything else reads this
      * tracker's state - keeps "what's currently tracked" always in sync with the
      * screen this exact event just read, which is what makes
      * [currentElapsedMillis] safe to call afterward in the same event (see its own
      * doc for why that matters for the Ad case). Returns the PREVIOUS video's
      * finished watch if this read represents a transition to something different -
      * null while still on the same video. */
    fun onScreenRead(identity: String?, category: VideoCategory, nowMillis: Long): FinishedWatch? {
        // UNIDENTIFIED reads share one "unknown" bucket (identity is always null,
        // so nothing distinguishes two different unidentifiable videos from each
        // other) - consecutive UNIDENTIFIED reads accumulate as one ongoing entry
        // rather than starting a new one on every single re-read, avoiding the
        // exact "hundreds of duplicate lines" spam class docs/feed_screen_gate/
        // PRD.md's own dedup-guard work already had to fix once for a different
        // mechanism.
        val effectiveIdentity = identity ?: "UNIDENTIFIED"
        if (effectiveIdentity == trackedIdentity) return null // still the same video
        val finished = trackedCategory?.let {
            FinishedWatch(it, (nowMillis - startedAtMillis).coerceAtMost(maxDurationMillis))
        }
        trackedIdentity = effectiveIdentity
        trackedCategory = category
        startedAtMillis = nowMillis
        return finished
    }

    /** RESOLVED premortem P1 (first draft of this PRD found this gap, then closed
      * it before implementation rather than leaving it for the ralph loop to hit):
      * the Ad case needs its own duration AT THE MOMENT the skip fires, not on some
      * later event once the video has already changed - [onScreenRead] alone can't
      * provide that, since it only reports a finished duration in arrears, on the
      * NEXT transition. This is a second, independent query against the SAME
      * tracked state [onScreenRead] just updated this same event - always call
      * [onScreenRead] first; by the time this is called, trackedIdentity/
      * startedAtMillis already reflect the video this event is looking at
      * (freshly reset to ~0 if this is its first read, already-elapsed correctly
      * if it's been on screen - and re-read as "still transitioning" - across
      * several prior events, e.g. the stuck-video case
      * docs/feed_screen_gate/PRD.md ss9.1 already logs a warning for). */
    fun currentElapsedMillis(nowMillis: Long): Long =
        (nowMillis - startedAtMillis).coerceAtMost(maxDurationMillis)
}
```

Wired into `TikTokFilterService.onAccessibilityEvent`: compute
`videoIdentity` unconditionally right after `isLive` (§1), determine
`category` from it + `decision` per §1's five-way mapping, call
`tracker.onScreenRead(...)` UNCONDITIONALLY and FIRST on every event
(before any skip/no-match branch), and:

- If `decision != null` (Ad, BlockedCreator, or RepeatView - about to
  skip right now): read `tracker.currentElapsedMillis(now)` and pass it
  into `StatsRepository.recordSkip`'s enriched line for that reason
  (§2.1) - correct because `onScreenRead` was just called this same
  event, so the tracker's state already reflects this exact video
  (freshly started if first seen, correctly accumulated if this is a
  repeated/stuck read of the same one).
- Otherwise, when `onScreenRead` returned a non-null `FinishedWatch`
  (a real transition away from whatever was previously tracked): apply
  §2.1's Unidentified/Post logging rules to that finished entry.

## 3a. Premortem: assume this pass fails again

- **P1 - the Ad-duration-at-skip-time gap - RESOLVED, §3 updated
  in this same pass rather than left for the ralph loop to discover.**
  First draft of this PRD found the gap (an Ad skip line would have
  enriched with the WRONG video's duration) and §3 was rewritten before
  moving on: `onScreenRead` is now always called first (transition
  detection only), and a separate `currentElapsedMillis` query - safe
  to call in the same event, since `onScreenRead` just synced the
  tracker's state to whatever this event is looking at - gets the Ad's
  own duration at the exact moment its skip fires.
- **P2 - RESOLVED (driver, 2026-09-09: "separate category").**
  `BLOCKED_CREATOR` and `REPEAT_VIEW` each get their own
  `VideoCategory` value (§1, §3) rather than folding into Post. Turned
  out free from a logging-volume standpoint (§2.1) since both already
  had their own `recordSkip` line before this PRD existed.
- **P3 - RESOLVED for v1 (driver, 2026-09-09: "start with that").**
  Ship with the proposed 3 seconds as-is rather than block
  implementation on further calibration. Still flagged as UNCONFIRMED
  against a full real-world session (§2.1) - picking it wrong in either
  direction (too low: back to the flooding problem §2 found; too high:
  genuinely-watched shorter videos silently undercounted) remains a
  real risk, just one the driver has chosen to accept for v1 and adjust
  later if it's visibly wrong rather than delay shipping on it.
- **P4 - `videoIdentity` computed unconditionally, every single event,
  is new CPU work on every accessibility callback, not just skip
  events.** `videoIdentity`'s own fallback path (when `extractHandle`
  fails) builds a filtered, joined signature of the entire current-video
  text block - not free, and this is now on the hot path for every
  genuine (non-skipped) view too, not just skips. Unconfirmed whether
  this matters in practice (accessibility events aren't extremely
  high-frequency), but worth watching for if a driver ever reports the
  app feeling slower after this ships.
- **P5 - the background-time cap (§0.4, driver's own chosen answer) still
  has a real edge case even with a cap.** A driver who leaves TikTok
  open on the SAME video, backgrounds the app for 2 minutes, returns,
  and then actually watches 10 more real seconds would show a capped
  duration that's still wrong (whatever the cap value is, not the real
  ~10s) - the cap bounds the damage, it doesn't produce a correct
  number. Worth stating plainly in whatever surfaces this data, not
  presented as precise.
- **P6 - RESOLVED for v1 (driver, 2026-09-09: "yes for now").** Found
  re-tracing §3's design a second time: a video's category can only
  change on an IDENTITY transition in the current design, but category
  and identity aren't actually guaranteed to move together.
  `onScreenRead` only updates `trackedCategory` when `effectiveIdentity`
  changes - if the SAME video is read first as POST (no ad marker
  rendered yet) and only classified AD on a LATER read (a delayed-
  rendering ad marker), the tracker would keep reporting POST for it,
  since its identity never changed. Driver accepted shipping with this
  limitation for now rather than solving mid-video reclassification
  first. Lower confidence this is a real, frequent problem than P1-P5:
  `docs/skip_dedup_root_cause/PRD.md`'s own investigation found the
  analogous risk was preloaded FUTURE videos' markers leaking into the
  current read (already scoped away by `currentVideoTexts`), not the
  CURRENT video's own classification changing mid-read - no evidence
  yet that this actually happens for a video that's genuinely still the
  one on screen. Not fixed here; flagged so a driver-reported "an ad
  showed as Post in my log" isn't a surprise if it happens.

## 4. Testing / verification approach

- `VideoWatchTrackerTest.kt` (new): transition detection, duration capping,
  UNIDENTIFIED bucket accumulation across consecutive null-identity
  reads, category classification from a `SkipDecision`/`videoIdentity`
  pair for all three categories.
- Re-run the real diagnostic log already on hand
  (`diagnostics10.log`, referenced throughout
  `docs/feed_screen_gate/PRD.md`) through the category-classification
  logic by hand to sanity-check the proposed 3s Post threshold (§2.1,
  P3) against real dwell-time-shaped data before committing to that
  number.
- Same disclosed limitation as every PRD in this repo: no Kotlin/JVM
  toolchain in this sandbox - traced by hand, pushed for the real CI to
  confirm.

## 5. Open questions - ANSWERED (driver, 2026-09-09)

- **P2**: should `BLOCKED_CREATOR`/`REPEAT_VIEW` skips be their own
  category(ies) instead of folding into Post? **-> Yes, "separate
  category."** See §1/§3's updated five-value `VideoCategory`.
- **P3's 3-second Post-logging threshold** - confirm or adjust before
  implementation. **-> "Start with that"** - ship with 3s, adjust later
  if the driver reports it's wrong.
- **P6**: acceptable to ship with the "category only updates on an
  identity transition" limitation, or does mid-video reclassification
  need solving first? **-> "Yes for now."**
- Is `SkipStreakGuard`-style in-memory-only state (reset on process
  death, same as every OTHER piece of state in `TikTokFilterService`)
  acceptable for `VideoWatchTracker`, or does the in-progress video's
  start time need to survive a service restart? Not re-asked this
  round - going with the PRD's own leaning (in-memory only, same as
  everything else in this file) since nothing in the driver's three
  answers pushed against it; revisit if the driver flags it later.

## 6. Success criteria (implementation-phase checklist)

- [x] P1 (Ad-duration-at-skip-time) resolved with a concrete design
      (§3) in this same scoping pass
- [x] `VideoWatchTracker` (or equivalent) added, pure/Android-free
- [x] `videoIdentity` computed unconditionally per event without
      changing the existing skip-dedup guard's own behavior
- [x] Category classification: Ad / BlockedCreator / RepeatView / Post /
      Unidentified, per §1's updated five-way mapping
- [x] Ad/BlockedCreator/RepeatView skip lines each enriched with
      duration; Unidentified always logged; Post logged only past the
      3s threshold (§2.1)
- [x] `VideoWatchTrackerTest.kt` written and traced by hand
- [x] Real diagnostic log re-run by hand against the classification
      logic and chosen threshold - see PROGRESS.md. Honest caveat: the
      log predates the word-boundary fix (docs/feed_screen_gate/
      PRD.md), so it's a rough order-of-magnitude sanity check of real
      transition gaps (roughly 1s to 30s+ between distinct matched
      states), not a clean post-fix genuine-Post-duration sample - 3s
      sits comfortably inside that range rather than at either extreme.
- [x] Pushed to a PR; CI green on the real commit - both `build` check
      runs on commit `db06cfc` completed with `conclusion: success`
      (https://github.com/ddann74/tiktok-feed-filter/actions/runs/34330047120/job/102396253351,
      https://github.com/ddann74/tiktok-feed-filter/actions/runs/34330042947/job/102396239562)
- [ ] Driver confirms: a real session's Activity log shows sensible
      Ad/Post/Unidentified entries, doesn't flood out other entries,
      and the durations look roughly right
- [ ] Driver sign-off
