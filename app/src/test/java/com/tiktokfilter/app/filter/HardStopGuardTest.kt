package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardStopGuardTest {

    @Test
    fun `does not hard-stop below the trip threshold`() {
        var state = TripHistoryState()
        var hardStop = false
        for (i in 1..2) {
            val (updated, wasHardStop) = HardStopGuard.recordTrip(
                state, nowMillis = i * 1000L, windowMillis = 300_000L, maxTripsInWindow = 3
            )
            state = updated
            hardStop = wasHardStop
        }
        assertEquals(2, state.tripTimestamps.size)
        assertFalse(hardStop)
    }

    @Test
    fun `hard-stops on the trip that reaches the threshold`() {
        var state = TripHistoryState()
        var hardStop = false
        for (i in 1..3) {
            val (updated, wasHardStop) = HardStopGuard.recordTrip(
                state, nowMillis = i * 1000L, windowMillis = 300_000L, maxTripsInWindow = 3
            )
            state = updated
            hardStop = wasHardStop
        }
        assertEquals(3, state.tripTimestamps.size)
        assertTrue(hardStop)
    }

    @Test
    fun `trips spread past the window don't accumulate toward a hard-stop`() {
        var state = TripHistoryState()
        // Two trips close together.
        for (i in 1..2) {
            val (updated, _) = HardStopGuard.recordTrip(
                state, nowMillis = i * 1000L, windowMillis = 300_000L, maxTripsInWindow = 3
            )
            state = updated
        }
        assertEquals(2, state.tripTimestamps.size)

        // A third trip, but long after the window - two isolated pause-and-resume
        // cycles many minutes apart isn't the same failure as three in a row.
        val (updated, hardStop) = HardStopGuard.recordTrip(
            state, nowMillis = 2000L + 400_000L, windowMillis = 300_000L, maxTripsInWindow = 3
        )
        // Both earlier trips (at 1000L, 2000L) are now older than the window relative to
        // 402000L, so only this new trip remains.
        assertEquals(1, updated.tripTimestamps.size)
        assertFalse(hardStop)
    }

    @Test
    fun `caller resetting after a hard-stop starts a genuinely fresh history`() {
        var state = TripHistoryState()
        for (i in 1..3) {
            val (updated, _) = HardStopGuard.recordTrip(
                state, nowMillis = i * 1000L, windowMillis = 300_000L, maxTripsInWindow = 3
            )
            state = updated
        }
        val (_, firstHardStop) = HardStopGuard.recordTrip(
            state, nowMillis = 3000L, windowMillis = 300_000L, maxTripsInWindow = 3
        )
        assertTrue(firstHardStop)

        // Matches TikTokFilterService's own usage: reset to TripHistoryState() once
        // the hard stop actually fires.
        val (afterReset, hardStopAgain) = HardStopGuard.recordTrip(
            TripHistoryState(), nowMillis = 4000L, windowMillis = 300_000L, maxTripsInWindow = 3
        )
        assertEquals(1, afterReset.tripTimestamps.size)
        assertFalse(hardStopAgain)
    }
}
