package com.tiktokfilter.app.filter

/** [count] consecutive skips seen so far in the current streak, [streakStartMillis] when
  * that streak began - a fresh streak (0, 0) is the natural starting point before any
  * skip has happened yet. */
data class SkipStreakState(val count: Int = 0, val streakStartMillis: Long = 0L)

/**
 * Pure decision logic for TikTokFilterService's runaway-auto-skip circuit breaker -
 * user-reported "auto scroll out of control" with no diagnostic-log evidence yet
 * pinpointing one specific cause (see docs/user_reported_fixes/PRD.md). Whatever the
 * actual trigger turns out to be (an over-broad keyword, a video-transition edge case,
 * something not yet seen), the skip gesture should never be able to fire indefinitely
 * without at least pausing and telling the user - the same "fail toward doing nothing,
 * and say so" pattern the Download in-flight lock already uses (see
 * TikTokActionCoordinator).
 *
 * Kept separate from TikTokFilterService (which owns the actual System.currentTimeMillis/
 * gesture-dispatch wiring) so the counting/reset logic itself is directly unit-testable,
 * the same reasoning FilterEngine and ActionSequence are pure too.
 */
object SkipStreakGuard {
    /** Call once per skip about to be performed. If more than [windowMillis] has passed
      * since the current streak started, the streak resets first (an old, cold streak
      * shouldn't count against a new one) - then the skip is counted. Returns the updated
      * state and whether this skip pushes the streak's count over [maxConsecutiveSkips];
      * the caller should perform the skip only when not tripped, and should reset to a
      * fresh [SkipStreakState] when it is, so the pause itself doesn't count toward
      * whatever streak comes after it. */
    fun recordSkip(
        current: SkipStreakState,
        nowMillis: Long,
        windowMillis: Long,
        maxConsecutiveSkips: Int
    ): Pair<SkipStreakState, Boolean> {
        val base = if (nowMillis - current.streakStartMillis > windowMillis) {
            SkipStreakState(count = 0, streakStartMillis = nowMillis)
        } else {
            current
        }
        val updated = base.copy(count = base.count + 1)
        return updated to (updated.count > maxConsecutiveSkips)
    }
}
