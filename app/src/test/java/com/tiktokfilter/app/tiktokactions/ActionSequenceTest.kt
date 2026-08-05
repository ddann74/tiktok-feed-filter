package com.tiktokfilter.app.tiktokactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSequenceTest {

    private val twoStageStages = listOf(listOf("More", "..."), listOf("Block"))

    @Test
    fun `starts at stage zero, not complete`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L)
        assertEquals(0, seq.stageIndex)
        assertFalse(seq.isComplete)
        assertEquals(listOf("More", "..."), seq.currentStageKeywords)
    }

    @Test
    fun `advance moves to the next stage`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L).advance()
        assertEquals(1, seq.stageIndex)
        assertFalse(seq.isComplete)
        assertEquals(listOf("Block"), seq.currentStageKeywords)
    }

    @Test
    fun `advancing past the last stage marks it complete`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L).advance().advance()
        assertTrue(seq.isComplete)
        assertEquals(emptyList<String>(), seq.currentStageKeywords)
    }

    @Test
    fun `has not timed out before the deadline`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L)
        assertFalse(seq.hasTimedOut(nowMillis = 1000L + 3999L, timeoutMillis = 4000L))
    }

    @Test
    fun `times out once past the deadline`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L)
        assertTrue(seq.hasTimedOut(nowMillis = 1000L + 4001L, timeoutMillis = 4000L))
    }

    @Test
    fun `advancing does not reset the original start time, so timeout still applies across stages`() {
        val seq = ActionSequence(twoStageStages, startedAtMillis = 1000L).advance()
        assertEquals(1000L, seq.startedAtMillis)
    }
}
