# Ralph loop — stabilize the skip/auto-like dedup fallback

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/skip_dedup_root_cause/PRD.md` §6 is checked.

---

You are implementing `docs/skip_dedup_root_cause/PRD.md` for the
`tiktok-feed-filter` repo, one checklist item at a time.

Each iteration:

1. Read `docs/skip_dedup_root_cause/PRD.md` §6 and
   `docs/skip_dedup_root_cause/PROGRESS.md` (create it if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `FilterEngine.videoIdentity` and
   the two call sites in `TikTokFilterService` named in PRD §3.2.
4. Match the codebase's voice: comments explain WHY (cite the real
   mechanism in PRD §1.2 - a stuck video re-skipped because the fallback
   identity was unstable across reads of the same screen), not what.
5. Check the box only after the change is made (or, for the CI item, only
   after a real green run is confirmed).
6. Append one entry to `docs/skip_dedup_root_cause/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `extractHandle` itself, ad-keyword matching, or
  `_safety_score`-equivalent logic - this PRD is scoped to the fallback
  used only when `extractHandle` already returned null.
- Do not remove the like/comment-count filtering in `videoIdentity` - it
  exists specifically so a ticking counter doesn't destabilize the
  signature the same way the old fallback was destabilized.
- The final box (user sign-off) is never yours to check.
