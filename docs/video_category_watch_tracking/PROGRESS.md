# Progress log — video category (Ad/BlockedCreator/RepeatView/Post/Unidentified) + watch-duration tracking

## Driver answers (2026-09-09)

Driver answered PRD §5's three open questions directly:

1. **P2**: "seperate category" - `BLOCKED_CREATOR`/`REPEAT_VIEW` each get
   their own `VideoCategory` value instead of folding into Post.
2. **P3**: "start with that" - ship with the proposed 3-second
   Post-logging threshold as-is.
3. **P6**: "yes for now" - acceptable to ship with the "category only
   updates on an identity transition" limitation.

The persistence question (in-memory-only state) wasn't re-asked - kept
at the PRD's own leaning (in-memory, same as everything else in
`TikTokFilterService`).

PRD moved from DRAFT to APPROVED; §1/§2.1/§3/§3a/§5/§6 updated to match
(five-value `VideoCategory` instead of three; Ad/BlockedCreator/
RepeatView all enrich their own existing skip line, not just Ad).

## Implementation (2026-09-09)

**`VideoWatchTracker.kt`** (new, `app/src/main/java/com/tiktokfilter/app/filter/`) -
pure, Android-free, same pattern as `SkipStreakGuard`/`HardStopGuard`.
`VideoCategory` enum (`AD, BLOCKED_CREATOR, REPEAT_VIEW, POST,
UNIDENTIFIED`), `FinishedWatch` data class, and the tracker itself:
`onScreenRead(identity, category, nowMillis)` (transition detection,
called first/unconditionally every event; unidentified reads share one
bucket key so consecutive unidentifiable videos don't spam separate
entries) and `currentElapsedMillis(nowMillis)` (same-event query, safe
to call right after `onScreenRead` - this is PRD §3/P1's resolution for
getting an Ad/BlockedCreator/RepeatView's own duration at the exact
moment it's skipped, not some later video's).

**`StatsRepository.kt`**: `recordSkip` gained an optional
`watchDurationMillis` parameter (default `null`, so every existing
caller/test keeps compiling and producing identical output) - when
present, appends `" - on screen Xs"` to the existing skip line instead
of adding a new Activity entry, for all three skip reasons now, not
just Ad (§2.1's update: BlockedCreator/RepeatView already had their own
line too, so the "separate category" answer turned out free from a
log-volume standpoint). Two new methods, `recordUnidentifiedWatch` and
`recordPostWatch`, for the two genuinely NEW entry types.

**`TikTokFilterService.kt`**: added an unconditional
`FilterEngine.videoIdentity(texts)` call (`trackedVideoIdentity`) right
after `isLive` - a SEPARATE call from the existing skip-dedup guard's
own `videoIdentity` (computed later, after `root.recycle()`), so this
feature can't touch that guard's behavior. Category classification
(§1's five-way mapping) + `videoWatchTracker.onScreenRead(...)` both run
unconditionally, before the `decision == null` branch splits - this is
what makes `currentElapsedMillis(now)` safe to call later, in the
`decision != null` branch, right where `recordSkip` is called. A
`FinishedWatch` for AD/BLOCKED_CREATOR/REPEAT_VIEW is a deliberate no-op
at the transition point (that category already got its own enriched
skip line at the moment it was actually skipped) - only UNIDENTIFIED
(always) and POST (only past `POST_WATCH_MIN_MILLIS = 3_000L`) produce
new Activity log entries. New constant
`MAX_TRACKED_WATCH_DURATION_MILLIS = 5 * 60_000L` (5 minutes, UNCONFIRMED)
bounds the background-time gap (PRD §0.4/§3a-P5).

## Testing (2026-09-09)

`VideoWatchTrackerTest.kt` (new, 8 tests): first-read-never-finishes,
repeated-same-video-never-finishes, transition reports the PREVIOUS
video's category+duration, duration capping at the configured maximum
(simulating a backgrounded-app gap), consecutive UNIDENTIFIED reads of
different underlying videos accumulate as one bucket (only closed out
by a transition to something identified), `currentElapsedMillis`
reading the state `onScreenRead` just synced in the same event (both
freshly-started and mid-stuck-video), and `currentElapsedMillis`'s own
cap. Traced every test by hand against the implementation (no
Kotlin/JVM toolchain in this sandbox, same disclosed limitation as
every PRD in this repo) - all match.

Re-ran the real diagnostic log already on hand
(`diagnostics10.log`) to sanity-check the 3s Post threshold: grepped
every `[FILTER] no match`/`AD matched` transition line and compared
timestamps. Real gaps between distinct matched states in this log run
roughly 1s to 30s+ (e.g. `17:28:38.979` no-match to `17:28:39.748`
AD-matched is under 1s; `17:29:38.988` no-match to `17:30:14.885`
AD-matched spans over 30s). Honest caveat: this log predates the
word-boundary fix (`docs/feed_screen_gate/PRD.md`), so most of its
transitions are duplicate-suppressed re-reads of the SAME stuck video,
not clean genuine-view samples - this is a rough order-of-magnitude
check, not a calibration. 3 seconds sits comfortably inside the
observed range rather than at either extreme, consistent with the
driver's "start with that" answer rather than contradicting it.

Pushed for the real CI to confirm.

**Confirmed green**: both `build` check runs on commit `db06cfc`
completed with `conclusion: success`
(https://github.com/ddann74/tiktok-feed-filter/actions/runs/34330047120/job/102396253351,
https://github.com/ddann74/tiktok-feed-filter/actions/runs/34330042947/job/102396239562),
both completed 2026-09-09T08:37:5{0,6}Z - the 8 new tests and the full
pre-existing suite all passed for real, confirming the hand-traced
logic was correct.

Remaining PRD §6 boxes: driver confirms with a real session, driver
sign-off.
