package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckVideoRetryGuardTest {

    @Test
    fun `keeps waiting before the stuck threshold elapses`() {
        val action = StuckVideoRetryGuard.decide(
            retryCount = 0, elapsedSinceLastAttemptMillis = 3_000L,
            stuckThresholdMillis = 5_000L, maxRetries = 2
        )
        assertEquals(StuckVideoAction.KeepWaiting, action)
    }

    @Test
    fun `retries once the stuck threshold elapses, with no retries used yet`() {
        val action = StuckVideoRetryGuard.decide(
            retryCount = 0, elapsedSinceLastAttemptMillis = 5_000L,
            stuckThresholdMillis = 5_000L, maxRetries = 2
        )
        assertTrue(action is StuckVideoAction.Retry)
        assertEquals(1, (action as StuckVideoAction.Retry).updatedRetryCount)
    }

    @Test
    fun `retries again on the second stuck read, incrementing the retry count`() {
        val action = StuckVideoRetryGuard.decide(
            retryCount = 1, elapsedSinceLastAttemptMillis = 5_000L,
            stuckThresholdMillis = 5_000L, maxRetries = 2
        )
        assertTrue(action is StuckVideoAction.Retry)
        assertEquals(2, (action as StuckVideoAction.Retry).updatedRetryCount)
    }

    @Test
    fun `gives up once maxRetries has already been used`() {
        val action = StuckVideoRetryGuard.decide(
            retryCount = 2, elapsedSinceLastAttemptMillis = 5_000L,
            stuckThresholdMillis = 5_000L, maxRetries = 2
        )
        assertEquals(StuckVideoAction.GiveUp, action)
    }

    @Test
    fun `never retries past maxRetries even with a much longer elapsed time`() {
        val action = StuckVideoRetryGuard.decide(
            retryCount = 2, elapsedSinceLastAttemptMillis = 120_000L,
            stuckThresholdMillis = 5_000L, maxRetries = 2
        )
        assertEquals(StuckVideoAction.GiveUp, action)
    }

    @Test
    fun `a real 101-second stuck episode retries exactly maxRetries times then gives up`() {
        // Mirrors the real diagnostics11.log episode: re-reads roughly every 300ms, but
        // only readings spaced stuckThresholdMillis apart from the last ATTEMPT (not
        // every re-read) actually trigger a decision here - the caller only calls this
        // once elapsedSinceLastAttemptMillis >= stuckThresholdMillis is already true.
        var retryCount = 0
        var elapsed = 5_000L
        val results = mutableListOf<StuckVideoAction>()
        repeat(4) {
            val action = StuckVideoRetryGuard.decide(retryCount, elapsed, 5_000L, maxRetries = 2)
            results.add(action)
            if (action is StuckVideoAction.Retry) {
                retryCount = action.updatedRetryCount
                elapsed = 5_000L // caller resets lastAttemptMillis to "now" on each retry
            }
        }
        assertTrue(results[0] is StuckVideoAction.Retry)
        assertTrue(results[1] is StuckVideoAction.Retry)
        assertEquals(StuckVideoAction.GiveUp, results[2])
        assertEquals(StuckVideoAction.GiveUp, results[3])
    }
}
