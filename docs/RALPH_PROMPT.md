# Ralph loop — TikTok Feed Filter whole-app hardening

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/PRD.md` §6 is checked.

---

You are implementing `docs/PRD.md` for the `tiktok-feed-filter` repo, one
checklist item at a time.

Each iteration:

1. Read `docs/PRD.md` §6 (Success criteria) and `docs/PROGRESS.md`.
2. Pick the FIRST unchecked box in §6, top to bottom — do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - P1: `liveIndicatorKeywords` UI in `activity_main.xml` +
     `MainActivity.kt` — copy the exact structure already used for
     `liveMoreOptionsKeywords` (a sibling list right next to it in both
     files) rather than inventing a new pattern.
   - P2: try/catch around `DownloadedVideoLocator.findRecentlyAddedVideo`'s
     call site in `TikTokActionCoordinator.locateAndExtractAudio` —
     match the existing `diagnosticLog.logError` / `statsRepository
     .recordEvent` style used at every other failure exit in that same
     function, and make sure `isAudioExtractionInFlight = false` still
     runs on the caught path.
   - P3: only after the open question in PRD §5.1 has been answered
     (ask the user if it's still unresolved when this box comes up —
     don't guess a tradeoff call silently).
   - New unit tests for P2/P3 (pure logic, `FilterEngineTest.kt` or a new
     test file under `app/src/test/`, matching the existing test naming
     style — backtick-quoted descriptive names).
   - Attempting `./gradlew test` — if the environment can't reach Gradle's
     plugin/dependency repos (a real, confirmed constraint in some
     sandboxed environments), say so plainly rather than claiming a test
     run succeeded that didn't actually happen.
4. Match the existing codebase's own voice: comments explain WHY, not
   what, cite real evidence the way existing comments already do
   ("Confirmed via a real diagnostic log: ...", specific counts/timings
   like the README's "906 of 912 screen reads").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for the
   test-run item, only after `./gradlew test` was actually attempted —
   report the real outcome, success or environment-blocked, not a guess).
6. Append one entry to `docs/PROGRESS.md`: what was done, what file(s)
   changed, and the real outcome of anything you attempted to run.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This PRD is scoped to P1-P3 plus their tests. Do not also try to verify
  or "improve" the Block/Download menu-label keywords, Subject Boost's
  auto-like, or anything else the README already correctly marks as
  unconfirmed-without-a-device — that's real, disclosed, out-of-scope
  work for a different pass, not something to silently attempt here.
- No Android SDK, emulator, or TikTok install is available in this
  environment (same constraint as this session's other Android repos).
  Never claim UI-only work (P1) was "tested" beyond `git diff` review and
  reading the resulting XML/Kotlin for correctness — say exactly that,
  not more.
- If an iteration finds the PRD itself needs a change (a missed case, a
  wrong assumption), stop and say so instead of improvising past it.
- The final box (user sign-off) is never yours to check.
