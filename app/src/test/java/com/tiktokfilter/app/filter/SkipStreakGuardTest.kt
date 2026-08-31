package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipStreakGuardTest {

    @Test
    fun `does not trip below the threshold`() {
        var state = SkipStreakState()
        var tripped = false
        for (i in 1..5) {
            val (updated, wasTripped) = SkipStreakGuard.recordSkip(
                state, nowMillis = i * 100L, windowMillis = 15_000L, maxConsecutiveSkips = 8
            )
            state = updated
            tripped = wasTripped
        }
        assertEquals(5, state.count)
        assertFalse(tripped)
    }

    @Test
    fun `trips on the skip that exceeds the threshold`() {
        var state = SkipStreakState()
        var tripped = false
        for (i in 1..9) {
            val (updated, wasTripped) = SkipStreakGuard.recordSkip(
                state, nowMillis = i * 100L, windowMillis = 15_000L, maxConsecutiveSkips = 8
            )
            state = updated
            tripped = wasTripped
        }
        // 9th skip pushes count to 9, over the max of 8.
        assertEquals(9, state.count)
        assertTrue(tripped)
    }

    @Test
    fun `a streak spread past the window resets instead of accumulating`() {
        var state = SkipStreakState()
        // 8 skips right at the start of the window - right at the threshold, not tripped.
        for (i in 1..8) {
            val (updated, _) = SkipStreakGuard.recordSkip(
                state, nowMillis = i * 100L, windowMillis = 15_000L, maxConsecutiveSkips = 8
            )
            state = updated
        }
        assertEquals(8, state.count)

        // A 9th skip, but long after the window elapsed - real, separate ad/creator skips
        // spread over minutes of normal browsing, not a runaway burst - should reset the
        // streak rather than trip immediately.
        val (updated, tripped) = SkipStreakGuard.recordSkip(
            state, nowMillis = 100L + 20_000L, windowMillis = 15_000L, maxConsecutiveSkips = 8
        )
        assertEquals(1, updated.count)
        assertFalse(tripped)
    }

    @Test
    fun `caller resetting after a trip starts a genuinely fresh streak`() {
        val (_, firstTripped) = SkipStreakGuard.recordSkip(
            SkipStreakState(count = 8, streakStartMillis = 0L),
            nowMillis = 100L, windowMillis = 15_000L, maxConsecutiveSkips = 8
        )
        assertTrue(firstTripped)

        // Matches TikTokFilterService's own usage: reset to SkipStreakState() once tripped.
        val (afterReset, trippedAgain) = SkipStreakGuard.recordSkip(
            SkipStreakState(), nowMillis = 200L, windowMillis = 15_000L, maxConsecutiveSkips = 8
        )
        assertEquals(1, afterReset.count)
        assertFalse(trippedAgain)
    }
}
