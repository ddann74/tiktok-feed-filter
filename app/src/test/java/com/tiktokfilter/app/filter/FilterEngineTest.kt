package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterEngineTest {

    private val defaultAdKeywords = listOf("Sponsored")

    @Test
    fun `no match returns null`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@someone", "a normal caption", "128 comments"),
            adKeywordsEnabled = true,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("someoneelse")
        )
        assertNull(decision)
    }

    @Test
    fun `ad keyword match fires AD`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand_official", "Check out our new product!", "Sponsored"),
            adKeywordsEnabled = true,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = true,
            blockedCreators = emptySet()
        )
        assertEquals(SkipReason.AD, decision?.reason)
        assertEquals("Sponsored", decision?.detail)
    }

    @Test
    fun `Ad starts in countdown text fires AD - the actual wording seen on a real device`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("Some Brand profile", "Ad starts in 5s", "a caption"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Sponsored", "Ad starts in"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertEquals(SkipReason.AD, decision?.reason)
        assertEquals("Ad starts in", decision?.detail)
    }

    @Test
    fun `ad keyword match is case-insensitive`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "sponsored content below"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Sponsored"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertEquals(SkipReason.AD, decision?.reason)
    }

    @Test
    fun `blocked creator handle fires BLOCKED_CREATOR`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@annoying_account", "some caption text", "42 comments"),
            adKeywordsEnabled = true,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoying_account")
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
        assertEquals("@annoying_account", decision?.detail)
    }

    @Test
    fun `blocked creator match is case-insensitive and ignores leading at-sign`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@AnnoyingAccount"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoyingaccount")
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
    }

    @Test
    fun `blocked creator takes priority over an ad keyword match on the same screen`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@annoying_account", "Sponsored"),
            adKeywordsEnabled = true,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoying_account")
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
    }

    @Test
    fun `disabled ad skipping never fires AD even on a match`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "Sponsored"),
            adKeywordsEnabled = false,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertNull(decision)
    }

    @Test
    fun `disabled blocked-creator skipping never fires BLOCKED_CREATOR even on a match`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@annoying_account"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = false,
            blockedCreators = setOf("annoying_account")
        )
        assertNull(decision)
    }

    @Test
    fun `a username-like word embedded in a longer sentence does not count as a handle`() {
        // "@brand" appears, but only as part of a longer caption string, not as its own
        // node reading exactly "@brand" - extractHandle should not treat this as the
        // video's creator handle.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("thanks for the shoutout @brand appreciate you!"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("brand")
        )
        assertNull(decision)
    }

    @Test
    fun `blank ad keywords in the list are ignored rather than matching everything`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "any caption at all"),
            adKeywordsEnabled = true,
            adKeywords = listOf("", "   "),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertNull(decision)
    }

    @Test
    fun `extractHandle finds the first standalone at-handle node`() {
        val handle = FilterEngine.extractHandle(listOf("128 comments", "@real_handle", "a caption"))
        assertEquals("@real_handle", handle)
    }

    @Test
    fun `normalizeHandle strips leading at-sign and lowercases`() {
        assertEquals("someuser", FilterEngine.normalizeHandle("@SomeUser"))
    }

    @Test
    fun `isLiveStream matches the LIVE badge case-insensitively`() {
        assertEquals(
            true,
            FilterEngine.isLiveStream(listOf("@some_host", "live", "1.2K watching"), listOf("LIVE"))
        )
    }

    @Test
    fun `isLiveStream does not match LIVE embedded inside a longer caption`() {
        // Only an exact standalone "LIVE" text node should count - a caption like
        // "come live your best life" should not be mistaken for the Live badge.
        assertEquals(
            false,
            FilterEngine.isLiveStream(listOf("@some_host", "come live your best life"), listOf("LIVE"))
        )
    }

    @Test
    fun `extractHandle falls back to the display name in a profile content description`() {
        // Confirmed against a real device's diagnostic log - current TikTok builds never
        // render a bare "@handle" node, only a "<name> profile" content description.
        val handle = FilterEngine.extractHandle(
            listOf("Adam Bannon | Cricket USA 🏏 profile", "Follow Adam Bannon | Cricket USA 🏏", "Like video 2,729 likes")
        )
        assertEquals("Adam Bannon | Cricket USA 🏏", handle)
    }

    @Test
    fun `extractHandle prefers a bare at-handle node over a profile content description if both exist`() {
        val handle = FilterEngine.extractHandle(listOf("@real_handle", "Someone Else profile"))
        assertEquals("@real_handle", handle)
    }

    @Test
    fun `extractHandle takes the first profile match when multiple videos' nodes are present`() {
        // TikTok preloads the next video's nodes too - the current video's is always
        // first in traversal order, which is what the real diagnostic log confirmed.
        val handle = FilterEngine.extractHandle(
            listOf("Current Creator profile", "Follow Current Creator", "Next Creator profile", "Follow Next Creator")
        )
        assertEquals("Current Creator", handle)
    }

    @Test
    fun `blocked creator matches via a display name pulled from a profile content description`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("John Kiriakou profile", "Follow John Kiriakou", "255 comments"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("john kiriakou")
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
        assertEquals("John Kiriakou", decision?.detail)
    }

    @Test
    fun `isLiveStream returns false for a normal video screen`() {
        assertEquals(
            false,
            FilterEngine.isLiveStream(listOf("@some_creator", "a normal caption", "128 comments"), listOf("LIVE"))
        )
    }
}
