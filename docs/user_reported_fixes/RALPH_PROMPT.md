# Ralph loop — user-reported reliability and navigation fixes

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/user_reported_fixes/PRD.md` §6 is checked.

---

You are implementing `docs/user_reported_fixes/PRD.md` for the
`tiktok-feed-filter` repo, one checklist item at a time.

Each iteration:

1. Read `docs/user_reported_fixes/PRD.md` §6 (Success criteria) and
   `docs/user_reported_fixes/PROGRESS.md`.
2. Pick the FIRST unchecked box in §6, top to bottom.
3. If the unchecked box is "item #4 clarified" - this is blocked on the
   user, not something to implement speculatively. Stop and ask, don't
   guess a design.
4. Otherwise implement exactly that item, matching the codebase's existing
   voice: comments explain WHY (cite the real user report, the real gap
   found in code/history), not what.
5. Check the box in PRD.md §6 only after the change is actually made (or,
   for a test, only after an honest attempt to run it - if this
   environment still has no Kotlin/JVM toolchain, say so explicitly rather
   than silently claiming a test passed).
6. Append one entry to `docs/user_reported_fixes/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not guess at what "pre-screened ads" means - the PRD's own
  investigation (§1.4) found no matching feature in this app's history.
  Get it from the user before writing any code for it.
- Do not change `DEFAULT_AD_KEYWORDS`, ad-keyword matching semantics
  (substring vs. word-boundary), or any existing default without new
  evidence (a diagnostic log, a user answer) - the circuit breaker (§3.1)
  is the chosen mitigation for the auto-scroll report specifically because
  it doesn't require guessing a root cause.
- Do not touch `FilterEngine`'s existing ad/blocked-creator/repeat-view
  logic, `ActionSequence`, or `TikTokActionCoordinator` - out of scope.
- `SkipStreakGuard` stays pure Kotlin (no Android import) - if a future
  iteration needs Android-specific behavior, put it in
  `TikTokFilterService`, not in the guard itself.
- The final box (user sign-off) is never yours to check.
