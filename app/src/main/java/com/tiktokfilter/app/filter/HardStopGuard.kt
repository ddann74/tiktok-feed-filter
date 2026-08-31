package com.tiktokfilter.app.filter

/** [tripTimestamps] when each recent circuit-breaker trip happened (millis) - only the
  * ones still within the escalation window matter, older ones are dropped on the next
  * call rather than kept forever. */
data class TripHistoryState(val tripTimestamps: List<Long> = emptyList())

/**
 * Escalation on top of [SkipStreakGuard] - see docs/auto_scroll_hard_stop/PRD.md. The
 * per-burst circuit breaker (SkipStreakGuard) pauses auto-skip for a while and then
 * resumes; if whatever is actually causing the runaway pattern is still there, it can
 * trip again, pause, resume, trip again, forever. A driver who reported "I want it
 * stopped" isn't asking for "throttled to bursts with pauses in between" - this tracks
 * how many times the per-burst breaker has tripped within a longer window and reports
 * when that itself crosses a threshold, so the caller can escalate to an actual stop
 * (turning the relevant settings off for real) instead of pausing again.
 *
 * Kept separate from TikTokFilterService/SettingsRepository (which own the actual
 * System.currentTimeMillis/SharedPreferences wiring) so the counting/pruning logic
 * itself is directly unit-testable, same reasoning as SkipStreakGuard itself.
 */
object HardStopGuard {
    /** Call once each time the per-burst circuit breaker (SkipStreakGuard) reports a
      * trip. Drops any recorded trip older than [windowMillis] before adding this one,
      * so a trip from long ago doesn't count against a new, unrelated burst. Returns the
      * updated state and whether this trip pushes the count in the window to/over
      * [maxTripsInWindow] - the caller should perform the actual hard stop only when
      * true, and should reset to a fresh [TripHistoryState] afterward so the hard stop
      * itself doesn't count toward whatever escalation comes after it. */
    fun recordTrip(
        current: TripHistoryState,
        nowMillis: Long,
        windowMillis: Long,
        maxTripsInWindow: Int
    ): Pair<TripHistoryState, Boolean> {
        val recentTrips = current.tripTimestamps.filter { nowMillis - it <= windowMillis }
        val updated = TripHistoryState(recentTrips + nowMillis)
        return updated to (updated.tripTimestamps.size >= maxTripsInWindow)
    }
}
