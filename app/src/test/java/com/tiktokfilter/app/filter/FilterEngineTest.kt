package com.tiktokfilter.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `an ad keyword does not match when it only appears inside a longer word`() {
        // CONFIRMED REAL BUG, real diagnostic log (docs/feed_screen_gate/PRD.md ss1.2/
        // ss1.3): a driver's short "Ad" keyword matched as a plain substring inside
        // TikTok's own "Add or remove this video from Favourites." chrome text, which is
        // present on nearly every video (ad or not) - firing on ordinary content, not
        // just real ads.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf(
                "@brand_official", "a completely normal caption",
                "Add or remove this video from Favourites."
            ),
            adKeywordsEnabled = true,
            adKeywords = listOf("Ad"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertNull(decision)
    }

    @Test
    fun `an ad keyword still matches its own standalone list element`() {
        // The real shape a genuine TikTok ad badge takes in the flat text list, per the
        // same log ("..., Ad, Learn more, Video, ...") - must keep working after the
        // word-boundary fix above.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand_official", "Check out our new product!", "Ad", "Learn more"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Ad"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertEquals(SkipReason.AD, decision?.reason)
        assertEquals("Ad", decision?.detail)
    }

    @Test
    fun `an ad keyword phrase still matches embedded in a longer sentence at a punctuation boundary`() {
        // Same word-boundary logic, exercised against punctuation rather than whitespace
        // on both sides - not just the already-covered "Ad starts in 5s" (space-bounded)
        // case above.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand_official", "This is (Sponsored) content"),
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
    fun `an ad keyword belonging to a preloaded next video does not skip the current video`() {
        // Reproduces a real diagnostic log: the currently-visible video (HistoryEgghead)
        // is not an ad, but TikTok has already preloaded the next video (John Kiriakou)
        // several slots ahead, and that one's "Ad starts in 5s" marker shows up in the
        // exact same screen read - well before the user has scrolled anywhere near it.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf(
                "HistoryEgghead profile", "Follow HistoryEgghead", "Like video 13.7K likes",
                "HistoryEgghead", "For 3,000 years they were cherished. Then the 1800s happened.", "Video",
                "John Kiriakou profile", "Follow John Kiriakou", "Like video 4,755 likes",
                "John Kiriakou", "The Viral Lie That Sent 60,000 Migrants to Spain", "Ad starts in 5s", "Video",
                "vhfg profile", "Follow vhfg"
            ),
            adKeywordsEnabled = true,
            adKeywords = listOf("Sponsored", "Ad starts in"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertNull(decision)
    }

    @Test
    fun `an ad keyword belonging to the actual current video does skip it`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf(
                "SomeBrand profile", "Follow SomeBrand", "SomeBrand", "Buy our thing", "Ad starts in 5s", "Video",
                "NextCreator profile", "Follow NextCreator"
            ),
            adKeywordsEnabled = true,
            adKeywords = listOf("Sponsored", "Ad starts in"),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertEquals(SkipReason.AD, decision?.reason)
        assertEquals("Ad starts in", decision?.detail)
    }

    @Test
    fun `a blocked creator further down in preloaded videos does not skip the current video`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf(
                "Current Creator profile", "Follow Current Creator", "a normal caption", "Video",
                "Annoying Account profile", "Follow Annoying Account"
            ),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoying account")
        )
        assertNull(decision)
    }

    @Test
    fun `subject boost never skips - a non-matching video is left alone by evaluate`() {
        // Subject Boost (formerly Subject Filter) no longer participates in evaluate() at
        // all - it only ever adds a positive signal (auto-like) via matchesSubject, never
        // a skip decision, regardless of whether the video matches any subject.
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("SomeCreator profile", "a caption about cooking pasta"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet()
        )
        assertNull(decision)
    }

    @Test
    fun `matchesSubject is true when the current video mentions a configured subject`() {
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf("SomeCreator profile", "a caption about ancient history"),
            subjectKeywords = listOf("history", "science")
        )
        assertEquals(true, matches)
    }

    @Test
    fun `matchesSubject is false when the current video mentions none of the configured subjects`() {
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf("SomeCreator profile", "a caption about cooking pasta"),
            subjectKeywords = listOf("history", "science")
        )
        assertEquals(false, matches)
    }

    @Test
    fun `matchesSubject is false when no subjects are configured yet`() {
        // Guards against the failure mode of turning Subject Boost on before adding any
        // subject - should never mean "matches literally everything".
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf("SomeCreator profile", "any caption at all"),
            subjectKeywords = emptyList()
        )
        assertEquals(false, matches)
    }

    @Test
    fun `a subject keyword does not match when it only appears inside a longer word`() {
        // Same class of bug as the ad-keyword fix above, same fix (containsWholeWord) -
        // "art" must not match inside "party".
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf("SomeCreator profile", "a caption about the party scene"),
            subjectKeywords = listOf("art")
        )
        assertEquals(false, matches)
    }

    @Test
    fun `a subject keyword still matches as its own word inside a longer caption`() {
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf("SomeCreator profile", "I love art museums"),
            subjectKeywords = listOf("art")
        )
        assertEquals(true, matches)
    }

    @Test
    fun `matchesSubject is scoped to the current video, not a preloaded one`() {
        val matches = FilterEngine.matchesSubject(
            screenTexts = listOf(
                "CurrentCreator profile", "a caption about cooking pasta", "Video",
                "NextCreator profile", "a caption about history"
            ),
            subjectKeywords = listOf("history")
        )
        assertEquals(false, matches)
    }

    @Test
    fun `a blocked creator is still skipped for that reason regardless of subject matching`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("Annoying Account profile", "a caption about history"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoying account")
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
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

    // --- videoFingerprint / repeat-view skip ---------------------------------------

    @Test
    fun `videoFingerprint combines the creator identity with a caption proxy`() {
        val fingerprint = FilterEngine.videoFingerprint(
            listOf(
                "HistoryEgghead profile", "Follow HistoryEgghead", "Like video 13.7K likes",
                "HistoryEgghead", "For 3,000 years they were cherished. Then the 1800s happened.", "Video"
            )
        )
        assertEquals("HistoryEgghead|For 3,000 years they were cherished. Then the 1800s happened.", fingerprint)
    }

    @Test
    fun `videoFingerprint returns null when no creator identity can be found at all`() {
        // Deliberately NOT falling back to some other text the way the transient same-tick
        // dedup elsewhere does - a wrong fingerprint here would corrupt a PERSISTED count.
        val fingerprint = FilterEngine.videoFingerprint(listOf("a normal caption", "128 comments"))
        assertNull(fingerprint)
    }

    @Test
    fun `videoFingerprint returns null for a captionless video instead of collapsing to a shared fingerprint`() {
        // CONFIRMED REAL BUG this guards against: no text survives the template-exclusion
        // filter (a genuinely captionless video), so there's nothing real to distinguish
        // it from every OTHER captionless video the same creator has ever posted. Falling
        // back to an empty caption proxy ("handle|") would collapse them all into one
        // shared, persisted fingerprint - exactly the bug that caused continuous,
        // unstoppable auto-skipping once repeat-view was actually used on a real feed.
        val fingerprint = FilterEngine.videoFingerprint(
            listOf("SomeCreator profile", "Follow SomeCreator", "SomeCreator", "Video")
        )
        assertNull(fingerprint)
    }

    @Test
    fun `videoFingerprint returns null when the only remaining text is too short to trust`() {
        // A single short reaction/emoji-only caption is exactly the kind of text that
        // could plausibly repeat across many different, unrelated videos - same collision
        // risk as the fully-captionless case above, just less extreme.
        val fingerprint = FilterEngine.videoFingerprint(
            listOf("SomeCreator profile", "Follow SomeCreator", "SomeCreator", "lol", "Video")
        )
        assertNull(fingerprint)
    }

    @Test
    fun `videoFingerprint differs for two different videos from the same creator`() {
        val first = FilterEngine.videoFingerprint(
            listOf("SomeCreator profile", "Follow SomeCreator", "SomeCreator", "First video's caption", "Video")
        )
        val second = FilterEngine.videoFingerprint(
            listOf("SomeCreator profile", "Follow SomeCreator", "SomeCreator", "A completely different caption", "Video")
        )
        assertEquals(false, first == second)
    }

    @Test
    fun `videoFingerprint is scoped to the current video, not a preloaded one`() {
        // Same current-video-scoping guarantee evaluate()/matchesSubject already have,
        // extended to the fingerprint used for repeat-view tracking.
        val fingerprint = FilterEngine.videoFingerprint(
            listOf(
                "CurrentCreator profile", "Follow CurrentCreator", "CurrentCreator", "Current video's caption", "Video",
                "NextCreator profile", "Follow NextCreator", "NextCreator", "Next video's caption"
            )
        )
        assertEquals("CurrentCreator|Current video's caption", fingerprint)
    }

    @Test
    fun `repeat-view skip fires once the count reaches the configured limit`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "a normal caption"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet(),
            repeatViewSkipEnabled = true,
            repeatViewCount = 3,
            repeatViewLimit = 3
        )
        assertEquals(SkipReason.REPEAT_VIEW, decision?.reason)
        assertEquals("3 views", decision?.detail)
    }

    @Test
    fun `repeat-view skip does not fire below the configured limit`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "a normal caption"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet(),
            repeatViewSkipEnabled = true,
            repeatViewCount = 2,
            repeatViewLimit = 3
        )
        assertNull(decision)
    }

    @Test
    fun `repeat-view skip never fires while disabled even at or above the limit`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@brand", "a normal caption"),
            adKeywordsEnabled = false,
            adKeywords = emptyList(),
            blockedCreatorsEnabled = false,
            blockedCreators = emptySet(),
            repeatViewSkipEnabled = false,
            repeatViewCount = 10,
            repeatViewLimit = 3
        )
        assertNull(decision)
    }

    @Test
    fun `blocked creator and ad both take priority over a repeat-view skip on the same video`() {
        val decision = FilterEngine.evaluate(
            screenTexts = listOf("@annoying_account", "Sponsored"),
            adKeywordsEnabled = true,
            adKeywords = defaultAdKeywords,
            blockedCreatorsEnabled = true,
            blockedCreators = setOf("annoying_account"),
            repeatViewSkipEnabled = true,
            repeatViewCount = 99,
            repeatViewLimit = 3
        )
        assertEquals(SkipReason.BLOCKED_CREATOR, decision?.reason)
    }

    // videoIdentity - see docs/skip_dedup_root_cause/PRD.md. Real bug this closes: the
    // old `extractHandle(texts) ?: texts.firstOrNull()` fallback could return a
    // DIFFERENT value across two reads of the exact same still-on-screen video (whenever
    // extractHandle failed), defeating TikTokFilterService's duplicate-skip guard and
    // letting a stuck video get re-skipped repeatedly - the actual mechanism behind a
    // driver-reported "auto scrolling out of control" incident, not just many different
    // real ads/creators.

    @Test
    fun `videoIdentity prefers the real creator identity when extractHandle succeeds`() {
        val texts = listOf("Some Brand profile", "a caption", "128 comments")
        assertEquals("Some Brand", FilterEngine.videoIdentity(texts))
    }

    @Test
    fun `videoIdentity is stable across two identical reads when extractHandle fails`() {
        // No "@handle" node, no "<name> profile" content description - extractHandle
        // returns null, same as a real device read where TikTok didn't render either.
        val texts = listOf("a caption with no creator info", "128 comments")
        val first = FilterEngine.videoIdentity(texts)
        val second = FilterEngine.videoIdentity(texts)
        assertEquals(first, second)
    }

    @Test
    fun `videoIdentity is unaffected by a changing like or comment count`() {
        val before = listOf("a caption with no creator info", "128 comments", "Like video 4.2K likes")
        val after = listOf("a caption with no creator info", "129 comments", "Like video 4.3K likes")
        assertEquals(FilterEngine.videoIdentity(before), FilterEngine.videoIdentity(after))
    }

    @Test
    fun `videoIdentity still distinguishes two different videos when extractHandle fails on both`() {
        val videoA = listOf("first video's caption", "128 comments")
        val videoB = listOf("a completely different caption", "50 comments")
        assertNotEquals(FilterEngine.videoIdentity(videoA), FilterEngine.videoIdentity(videoB))
    }

    @Test
    fun `videoIdentity returns null only when there is truly nothing to identify the video by`() {
        val texts = listOf("128 comments", "Like video 4.2K likes")
        assertNull(FilterEngine.videoIdentity(texts))
    }

    @Test
    fun `videoIdentity does not collide on the generic Video marker two different captionless videos share`() {
        // Both videos render nothing but TikTok's generic "Video" chrome label and a
        // count - no real distinguishing content at all. Filtering out "Video" (the
        // same template exclusion videoFingerprint already applies) means both
        // correctly return null rather than both returning the same non-null "Video"
        // string, which would have wrongly treated two different videos as one.
        val videoA = listOf("Video", "50 comments")
        val videoB = listOf("Video", "999 comments")
        assertNull(FilterEngine.videoIdentity(videoA))
        assertNull(FilterEngine.videoIdentity(videoB))
    }
}
