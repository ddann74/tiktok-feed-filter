# Ralph loop — ad/subject keyword matching must respect word boundaries

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/feed_screen_gate/PRD.md` §6 is checked.

---

You are implementing `docs/feed_screen_gate/PRD.md` for the
`tiktok-feed-filter` repo, one checklist item at a time.

Each iteration:

1. Read `docs/feed_screen_gate/PRD.md` §6 and
   `docs/feed_screen_gate/PROGRESS.md` (create it if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `FilterEngine.evaluate`'s
   ad-keyword check and `FilterEngine.matchesSubject`, per PRD §3.
4. Match the codebase's voice: comments explain WHY (cite the real
   log evidence in PRD §1.2/§1.3 - the configured keyword `"Ad"`
   matching inside TikTok's own ubiquitous `"Add or remove this video
   from Favourites."` chrome, confirmed present on 663 of 762 real log
   lines), not what.
5. Check the box only after the change is made (or, for the CI item,
   only after a real green run is confirmed).
6. Append one entry to `docs/feed_screen_gate/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `isLiveStream` or `extractHandle`/blocked-creator
  matching - PRD §1.5/§2 already confirmed neither has this bug.
- Do not widen this into a "does this screen look like the TikTok feed"
  structural check - that's PRD §5's explicitly open, NOT decided,
  follow-up question, not this PRD's scope.
- Do not change `DEFAULT_AD_KEYWORDS`, `DEFAULT_TARGET_PACKAGES`, or any
  other default/config value - this PRD only changes HOW a configured
  keyword is matched, not what's configured by default.
- The final box (driver sign-off) is never yours to check.
