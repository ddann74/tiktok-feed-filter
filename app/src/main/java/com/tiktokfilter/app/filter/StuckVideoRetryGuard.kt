package com.tiktokfilter.app.filter

/** What to do about a video whose duplicate-skip guard has fired (TikTokFilterService's
  * own `lastSkippedVideoIdentity == videoIdentity` check) - see
  * docs/feed_screen_gate/PRD.md §14 for the full design/premortem. */
sealed class StuckVideoAction {
    /** Try the skip gesture again. [updatedRetryCount] is what the caller's own retry
      * counter should become - one higher than what was passed in. */
    data class Retry(val updatedRetryCount: Int) : StuckVideoAction()

    /** [maxRetries] has already been used up - stop retrying, warn instead. */
    object GiveUp : StuckVideoAction()

    /** Not stuck long enough yet to rule out "still normally transitioning" - do
      * nothing, same as before this guard existed. */
    object KeepWaiting : StuckVideoAction()
}

/**
 * Pure decision logic for retrying a skip gesture that doesn't appear to have taken
 * effect - docs/feed_screen_gate/PRD.md §11.4/§14. Real diagnostic logs showed a video
 * stuck on screen for over 100 seconds after a skip was attempted, with nothing beyond
 * a one-time warning (docs/feed_screen_gate/PRD.md §9.1) ever trying anything different.
 *
 * Deliberately bounded (see [maxRetries]) rather than retrying forever: a genuinely
 * failed gesture retried with the exact same shape is unlikely to succeed on the Nth
 * attempt if it didn't on the first few, and retrying without limit would defeat the
 * whole point of the duplicate-skip guard this sits behind (docs/skip_dedup_root_cause/
 * PRD.md) - turning "one video got stuck" into "this app hammers the screen with swipes
 * forever," the exact "auto scrolling out of control" failure this app already exists to
 * prevent.
 *
 * Kept separate from TikTokFilterService (which owns the actual System.currentTimeMillis/
 * gesture-dispatch/state-field wiring) so the retry-vs-give-up decision itself is
 * directly unit-testable, the same reasoning SkipStreakGuard/HardStopGuard are pure too.
 */
object StuckVideoRetryGuard {
    /** [retryCount] retries already attempted for the CURRENTLY-stuck video (0 before
      * the first retry - reset by the caller whenever a genuinely new video is skipped).
      * [elapsedSinceLastAttemptMillis] time since the last skip/retry attempt on this
      * same video - reusing TikTokFilterService's own `lastSkipMillis` (updated on every
      * attempt, including retries) means this doubles as "how long has it been since we
      * last tried anything," without a second timestamp field to keep in sync.
      * [stuckThresholdMillis] how long to wait before considering a re-read "genuinely
      * stuck" rather than "still normally transitioning" - the caller reuses the same
      * threshold its own one-time warning already used (docs/feed_screen_gate/PRD.md
      * §9.1's STUCK_VIDEO_WARNING_MILLIS), so a retry and the give-up warning are spaced
      * by the same real-world-calibrated gap. */
    fun decide(
        retryCount: Int,
        elapsedSinceLastAttemptMillis: Long,
        stuckThresholdMillis: Long,
        maxRetries: Int
    ): StuckVideoAction {
        if (elapsedSinceLastAttemptMillis < stuckThresholdMillis) return StuckVideoAction.KeepWaiting
        if (retryCount >= maxRetries) return StuckVideoAction.GiveUp
        return StuckVideoAction.Retry(retryCount + 1)
    }
}
