# Ralph loop — make the runaway auto-skip actually stop

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/auto_scroll_hard_stop/PRD.md` §6 is checked.

---

You are implementing `docs/auto_scroll_hard_stop/PRD.md` for the
`tiktok-feed-filter` repo, one checklist item at a time.

Each iteration:

1. Read `docs/auto_scroll_hard_stop/PRD.md` §6 (Success criteria) and
   `docs/auto_scroll_hard_stop/PROGRESS.md` (create it if it doesn't exist
   yet).
2. Pick the FIRST unchecked box in §6, top to bottom - do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item, scoped to `SkipStreakGuard`/its escalation
   counterpart, `TikTokFilterService`'s wiring of it, and
   `SettingsRepository`'s `isAdSkipEnabled`/`isBlockedCreatorSkipEnabled`
   setters only.
4. Match the codebase's existing voice: comments explain WHY (cite the
   real driver report, the real gap - a pause that repeats forever isn't a
   stop), not what.
5. Check the box in PRD.md §6 only after the change is made (or, for the
   test-execution item, only after CI actually ran it green - check the
   real workflow run, don't just write the test and assume).
6. Append one entry to `docs/auto_scroll_hard_stop/PROGRESS.md`: what was
   done, what file(s) changed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do NOT change `DEFAULT_AD_KEYWORDS`, ad-keyword matching semantics
  (substring vs. word-boundary), `_safety_score`-equivalent logic, or any
  other heuristic's default values - PRD §2 non-goals, still no diagnostic
  log evidence to justify one.
- Do NOT change `MAX_CONSECUTIVE_SKIPS`/`SKIP_STREAK_WINDOW_MILLIS`
  (the existing per-burst thresholds) - this PRD adds an escalation
  layer ON TOP of them, it doesn't retune them.
- The hard-stop must flip real `SettingsRepository` toggles (persisted),
  not just an in-memory flag - the whole point is that it survives past
  the current session, matching what "stopped" means to a driver checking
  Setup afterward.
- Never auto-disable Subject Boost, repeat-view skip, or the Block/
  Download automations - only Skip ads and Skip blocked creators, the two
  actually implicated in this failure mode.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
