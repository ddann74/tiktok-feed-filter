package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoWatchTrackerTest {

    @Test
    fun `the first video ever read never produces a finished watch`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        val finished = tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 1_000L)
        assertNull(finished)
    }

    @Test
    fun `repeated reads of the same still-on-screen video never produce a finished watch`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 1_000L)
        // Same identity, several later reads - nothing "finished" until it actually changes.
        assertNull(tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 1_500L))
        assertNull(tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 2_000L))
    }

    @Test
    fun `transitioning to a different video reports the previous one's category and duration`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 1_000L)
        tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 3_500L)
        val finished = tracker.onScreenRead("creatorB", VideoCategory.AD, nowMillis = 4_000L)
        // creatorA was tracked from 1_000L until this transition at 4_000L - 3s watched.
        assertEquals(VideoCategory.POST, finished?.category)
        assertEquals(3_000L, finished?.durationMillis)
    }

    @Test
    fun `duration is capped at the configured maximum, not left unbounded`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 5_000L)
        tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 0L)
        // Simulates the app being backgrounded mid-video (PRD §0.4/§3a-P5) - no events
        // fire in between, so the next read is a huge wall-clock jump, not 10 real
        // seconds of on-screen time.
        val finished = tracker.onScreenRead("creatorB", VideoCategory.POST, nowMillis = 600_000L)
        assertEquals(5_000L, finished?.durationMillis)
    }

    @Test
    fun `consecutive unidentified reads of different underlying videos accumulate as one bucket`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        // Two genuinely different videos, neither identifiable (identity always null) -
        // both share the tracker's internal "UNIDENTIFIED" bucket key (see the class's
        // own doc), so this must NOT report a finished watch between them.
        tracker.onScreenRead(identity = null, category = VideoCategory.UNIDENTIFIED, nowMillis = 1_000L)
        val betweenTwoUnidentifiedReads =
            tracker.onScreenRead(identity = null, category = VideoCategory.UNIDENTIFIED, nowMillis = 2_000L)
        assertNull(betweenTwoUnidentifiedReads)

        // Only a transition to something IDENTIFIED finally closes out the accumulated
        // unidentified bucket, as one entry covering the whole stretch since 1_000L.
        val finished = tracker.onScreenRead("creatorA", VideoCategory.POST, nowMillis = 4_000L)
        assertEquals(VideoCategory.UNIDENTIFIED, finished?.category)
        assertEquals(3_000L, finished?.durationMillis)
    }

    @Test
    fun `currentElapsedMillis reads the same state onScreenRead just synced, in the same event`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        // First read of a brand-new ad - onScreenRead resets startedAtMillis to this
        // exact event's time, so elapsed is ~0, matching a video that's about to be
        // skipped the instant it's detected (PRD §3/P1's resolved Ad-duration design).
        tracker.onScreenRead("creatorA", VideoCategory.AD, nowMillis = 10_000L)
        assertEquals(0L, tracker.currentElapsedMillis(nowMillis = 10_000L))
    }

    @Test
    fun `currentElapsedMillis grows across repeated reads of the same stuck video`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 60_000L)
        tracker.onScreenRead("creatorA", VideoCategory.AD, nowMillis = 10_000L)
        // Same video re-read several times (e.g. the stuck-video case docs/
        // feed_screen_gate/PRD.md §9.1 already logs a warning for) - onScreenRead
        // returns null each time (no transition), but elapsed keeps growing correctly.
        tracker.onScreenRead("creatorA", VideoCategory.AD, nowMillis = 10_300L)
        assertEquals(300L, tracker.currentElapsedMillis(nowMillis = 10_300L))
    }

    @Test
    fun `currentElapsedMillis is also capped at the configured maximum`() {
        val tracker = VideoWatchTracker(maxDurationMillis = 5_000L)
        tracker.onScreenRead("creatorA", VideoCategory.AD, nowMillis = 0L)
        assertEquals(5_000L, tracker.currentElapsedMillis(nowMillis = 999_999L))
    }
}
