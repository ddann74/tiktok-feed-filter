# Ralph loop — video category (Ad/Post/Unidentified) + watch-duration tracking

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/video_category_watch_tracking/PRD.md` §6 is checked.

**Do not start iterating until `docs/video_category_watch_tracking/PRD.md`
§5's open questions (P2, P3, P6, and the persistence question) have real
driver answers.** This PRD is DRAFT status specifically because those are
still open - unlike this repo's other two PRDs at the time this was
written, this one was scoped, not yet approved to implement.

---

You are implementing `docs/video_category_watch_tracking/PRD.md` for the
`tiktok-feed-filter` repo, one checklist item at a time.

Each iteration:

1. Read `docs/video_category_watch_tracking/PRD.md` §6 and
   `docs/video_category_watch_tracking/PROGRESS.md` (create it if
   missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `VideoWatchTracker` (new
   file, `app/src/main/java/com/tiktokfilter/app/filter/`, matching
   where `SkipStreakGuard`/`HardStopGuard`/`RepeatViewRepository`
   already live) and its two call sites in `TikTokFilterService.kt`
   (the unconditional `videoIdentity` computation near `isLive`, and
   the Ad-skip/Post-transition logging hooks in §3's own design).
4. Match the codebase's voice: comments explain WHY (cite PRD §1-§3 -
   the driver's own Ad/Post/Unidentified categories, the 50-entry
   Activity log cap §2 found, the resolved Ad-duration-at-skip-time
   design §3/P1), not what.
5. Check the box only after the change is made (or, for the CI item,
   only after a real green run is confirmed).
6. Append one entry to `docs/video_category_watch_tracking/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not change the EXISTING skip-dedup guard's own behavior
  (`lastSkippedVideoIdentity` in `TikTokFilterService.kt`) - `videoIdentity`
  becomes unconditionally computed per PRD §1, but the dedup check
  itself, and everything in `docs/skip_dedup_root_cause/PRD.md`/
  `docs/feed_screen_gate/PRD.md`, stays exactly as it is. This PRD reuses
  that value; it does not redesign what it's for.
- Do not raise `StatsRepository.MAX_LOG_ENTRIES` or otherwise change
  the Activity log's cap/storage as a way around PRD §2's volume
  problem - the Ad-enrich/Unidentified-always/Post-threshold split in
  §2.1 is the approved resolution; if it turns out not to be enough,
  that's a new open question for the driver, not a unilateral change
  here.
- Do not fold `BLOCKED_CREATOR`/`REPEAT_VIEW` into a category other
  than what the driver's own answer to PRD §5/P2 settled on.
- Do not touch `FilterEngine.evaluate`, `containsWholeWord`,
  `looksLikeFeedScreen`, or any other function from
  `docs/feed_screen_gate/PRD.md`'s own work - this PRD only reads their
  existing outputs (`SkipDecision`, `videoIdentity`).
- The final box (driver sign-off) is never yours to check.
