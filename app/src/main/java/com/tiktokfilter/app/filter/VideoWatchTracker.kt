package com.tiktokfilter.app.filter

/** Ad/BlockedCreator/RepeatView each mirror one of [SkipReason]'s own three values -
  * kept as their OWN categories rather than folded into POST, per
  * docs/video_category_watch_tracking/PRD.md §5/P2 (driver: "separate category").
  * Post is a genuine, non-skipped view with a real identity; Unidentified is anything
  * (skipped or not) FilterEngine.videoIdentity couldn't identify at all - see §1. */
enum class VideoCategory { AD, BLOCKED_CREATOR, REPEAT_VIEW, POST, UNIDENTIFIED }

/** One completed video's category and how long it was on screen, capped at whatever
  * [VideoWatchTracker] was constructed with - see that class's own [maxDurationMillis]
  * doc for why a cap exists at all (PRD §0.4/§3a-P5). */
data class FinishedWatch(val category: VideoCategory, val durationMillis: Long)

/**
 * Pure, Android-free per-video watch-time tracker for
 * docs/video_category_watch_tracking/PRD.md - same "testable without a device" pattern
 * as SkipStreakGuard/HardStopGuard/ActionSequence in this same package. Tracks exactly
 * one video at a time (whichever [onScreenRead] was most recently told about) and
 * reports a [FinishedWatch] only once the caller reports a DIFFERENT video, never on a
 * repeated read of the same still-on-screen one.
 *
 * [maxDurationMillis]: accessibility events only fire while TikTok is foreground (PRD
 * §0.4), so there's no direct "the app went to background for N minutes" signal - a
 * driver backgrounding TikTok mid-video and returning later would otherwise report a
 * multi-minute "duration" that's really just wall-clock time, not watch time. Capping
 * bounds the damage; it does NOT produce a correct number for that case (PRD §3a-P5,
 * disclosed there rather than presented as solved here).
 */
class VideoWatchTracker(private val maxDurationMillis: Long) {
    private var trackedIdentity: String? = null
    private var trackedCategory: VideoCategory? = null
    private var startedAtMillis: Long = 0L

    /** Call on EVERY accessibility event, first, before anything else reads this
      * tracker's state - keeps "what's currently tracked" always in sync with the
      * screen this exact event just read, which is what makes [currentElapsedMillis]
      * safe to call afterward in the same event (see its own doc for why that matters
      * for the Ad/BlockedCreator/RepeatView case). Returns the PREVIOUS video's
      * finished watch if this read represents a transition to something different -
      * null while still on the same video. */
    fun onScreenRead(identity: String?, category: VideoCategory, nowMillis: Long): FinishedWatch? {
        // UNIDENTIFIED reads share one bucket key (identity is always null, so nothing
        // distinguishes two different unidentifiable videos from each other) -
        // consecutive UNIDENTIFIED reads accumulate as one ongoing entry rather than
        // starting a new one on every single re-read, avoiding the exact "hundreds of
        // duplicate lines" spam class docs/feed_screen_gate/PRD.md's own dedup-guard
        // work already had to fix once for a different mechanism.
        val effectiveIdentity = identity ?: UNIDENTIFIED_BUCKET_KEY
        if (effectiveIdentity == trackedIdentity) return null // still the same video
        val finished = trackedCategory?.let {
            FinishedWatch(it, (nowMillis - startedAtMillis).coerceAtMost(maxDurationMillis))
        }
        trackedIdentity = effectiveIdentity
        trackedCategory = category
        startedAtMillis = nowMillis
        return finished
    }

    /** RESOLVED premortem P1 (PRD §3a): the Ad/BlockedCreator/RepeatView case needs its
      * own duration AT THE MOMENT the skip fires, not on some later event once the video
      * has already changed - [onScreenRead] alone can't provide that, since it only
      * reports a finished duration in arrears, on the NEXT transition. This is a second,
      * independent query against the SAME tracked state [onScreenRead] just updated this
      * same event - always call [onScreenRead] first; by the time this is called,
      * trackedIdentity/startedAtMillis already reflect the video this event is looking
      * at (freshly reset to ~0 if this is its first read, already-elapsed correctly if
      * it's been on screen - and re-read as "still transitioning" - across several prior
      * events, e.g. the stuck-video case docs/feed_screen_gate/PRD.md §9.1 already logs
      * a warning for). */
    fun currentElapsedMillis(nowMillis: Long): Long =
        (nowMillis - startedAtMillis).coerceAtMost(maxDurationMillis)

    private companion object {
        const val UNIDENTIFIED_BUCKET_KEY = "UNIDENTIFIED"
    }
}
