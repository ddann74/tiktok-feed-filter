package com.tiktokfilter.app.filter

enum class SkipReason { AD, BLOCKED_CREATOR }

/** [detail] is the creator handle for BLOCKED_CREATOR or the matched keyword for AD -
  * both go straight into the activity log so a skip is explainable after the fact. */
data class SkipDecision(val reason: SkipReason, val detail: String)

/**
 * Pure decision logic - no Android/AccessibilityNodeInfo dependencies, so it's directly
 * unit-testable. TikTokFilterService is responsible for turning the current screen into
 * a flat list of on-screen text strings and calling [evaluate]; this only decides whether
 * that amounts to "skip" and why.
 *
 * Both signals are inherently heuristic: there's no official API for "is this an ad" or
 * "who posted this", only reading whatever text TikTok happens to be rendering right now.
 * TikTok changing its UI wording or layout can silently break either one - see the
 * project README for how to notice and fix that without a rebuild. Both are also scoped
 * to just the video actually on screen (see [currentVideoTexts]) - the flat text list
 * passed in also contains preloaded, not-yet-visible videos further down the feed, and
 * matching against those would react to a video the user hasn't scrolled to yet.
 */
object FilterEngine {

    // TikTok renders the current video's creator as its own text node reading exactly
    // "@handle" (not embedded in a longer sentence) - this matches that shape specifically,
    // rather than any "@word" appearing inside a caption or comment. Kept as the first
    // choice below since it's the more precise signal when it exists, but real-device
    // logs show current TikTok builds don't actually render this node at all - see
    // profileContentDescriptionRegex below, which is what actually fires in practice.
    private val handleRegex = Regex("^@[A-Za-z0-9_.]{1,40}$")

    // What TikTok renders instead, in practice: the creator's *display name* (not their
    // @username - TikTok doesn't expose that to accessibility services) as its own
    // content-description reading "<name> profile" (a duplicate "Follow <name>" node is
    // always present too, but this one is matched first in traversal order and doesn't
    // need a second pattern). Confirmed against real diagnostic logs, where the first
    // "<name> profile" match in the flat text list consistently tracked whichever video
    // was actually on screen (preloaded next-up videos' nodes exist further down the
    // tree, after the current one's).
    private val profileContentDescriptionRegex = Regex("^(.+) profile$", RegexOption.IGNORE_CASE)

    fun evaluate(
        screenTexts: List<String>,
        adKeywordsEnabled: Boolean,
        adKeywords: List<String>,
        blockedCreatorsEnabled: Boolean,
        // Expected already-normalized (lowercase, no leading '@') - see normalizeHandle.
        blockedCreators: Set<String>
    ): SkipDecision? {
        // TikTok preloads several videos ahead, and every one of their nodes shows up in
        // the same flat text list [TikTokFilterService.collectText] produces - a real
        // diagnostic log confirmed a preloaded, not-yet-visible video's "Ad starts in 5s"
        // marker sitting in the *same* screen read as the video actually on screen,
        // several videos before it. Matching against the raw list would react to a video
        // the user hasn't scrolled to yet; currentVideoTexts keeps every check below
        // scoped to only the video actually visible.
        val visibleVideoTexts = currentVideoTexts(screenTexts)

        // Blocked-creator match is checked first: it's a specific, near-exact signal,
        // whereas ad-keyword matching is a plain substring search and more prone to a
        // false positive (e.g. a caption that happens to mention "sponsored").
        if (blockedCreatorsEnabled) {
            val handle = extractHandle(visibleVideoTexts)
            if (handle != null && blockedCreators.contains(normalizeHandle(handle))) {
                return SkipDecision(SkipReason.BLOCKED_CREATOR, handle)
            }
        }
        if (adKeywordsEnabled) {
            val matchedKeyword = adKeywords.firstOrNull { keyword ->
                keyword.isNotBlank() && visibleVideoTexts.any { it.contains(keyword, ignoreCase = true) }
            }
            if (matchedKeyword != null) {
                return SkipDecision(SkipReason.AD, matchedKeyword)
            }
        }
        return null
    }

    /** Subject Boost's match check: does the current video's own on-screen text mention
      * at least one configured subject? Deliberately NOT wired into [evaluate] as a skip
      * reason - Subject Boost never force-skips anything (that was the old Subject
      * Filter's behavior, which caused a "continuous scrolling" feel with no visible
      * stopping point). Instead the caller uses this purely as a positive signal (e.g.
      * auto-liking a match) while leaving normal browsing/scrolling entirely alone.
      * Blank keywords are filtered out the same way as [evaluate]'s ad-keyword check;
      * an empty/all-blank list always returns false rather than matching everything. */
    fun matchesSubject(screenTexts: List<String>, subjectKeywords: List<String>): Boolean {
        val meaningfulSubjects = subjectKeywords.filter { it.isNotBlank() }
        if (meaningfulSubjects.isEmpty()) return false
        val visibleVideoTexts = currentVideoTexts(screenTexts)
        return meaningfulSubjects.any { subject ->
            visibleVideoTexts.any { it.contains(subject, ignoreCase = true) }
        }
    }

    /** Truncates [screenTexts] to just the current video's own contiguous block - from
      * the start of the list up to (but not including) the *second* "<name> profile"
      * match, if there is one. TikTok's traversal order puts one video's own nodes
      * together, starting at its "<name> profile" node, immediately followed by the
      * next (preloaded) video's nodes - so the second such match marks where the
      * current video's own content ends. If there's only one match (or none - e.g. a
      * Live room, which doesn't use this pattern), the whole list is already scoped
      * correctly, so it's returned unchanged. */
    private fun currentVideoTexts(screenTexts: List<String>): List<String> {
        val profileIndexes = screenTexts.indices.filter { profileContentDescriptionRegex.matches(screenTexts[it].trim()) }
        if (profileIndexes.size < 2) return screenTexts
        return screenTexts.subList(0, profileIndexes[1])
    }

    /** Despite the name, this returns whatever TikTok actually exposes for the current
      * video's creator - a real "@handle" node if one exists (rare in practice), otherwise
      * the display name pulled out of the first "<name> profile" content description
      * (see [profileContentDescriptionRegex]'s doc). Either way, the result is what
      * [SettingsRepository]'s Blocked Creators entries need to match against - which in
      * practice today means entering the creator's on-screen display name, not their
      * @username. */
    fun extractHandle(screenTexts: List<String>): String? {
        screenTexts.firstOrNull { handleRegex.matches(it.trim()) }?.let { return it }
        return screenTexts.firstNotNullOfOrNull { text ->
            profileContentDescriptionRegex.matchEntire(text.trim())?.groupValues?.get(1)
        }
    }

    fun normalizeHandle(handle: String): String =
        handle.trim().removePrefix("@").lowercase()

    /** Whether the current screen looks like a TikTok Live room rather than a normal
      * FYP video - a plain substring match against [liveIndicatorKeywords] (default:
      * "LIVE", the badge TikTok renders on every live room), same heuristic shape as
      * ad-keyword matching. This exists because Live rooms use a different on-screen
      * layout than a normal video - the blocked-creator handle match above still works
      * the same way (a Live room's host handle is just another text node), but the
      * Block/Download tap sequences need to know they're on a Live screen so they can
      * use the right menu structure (see TikTokActionCoordinator, SettingsRepository's
      * liveBlockActionStages). */
    fun isLiveStream(screenTexts: List<String>, liveIndicatorKeywords: List<String>): Boolean =
        liveIndicatorKeywords.any { keyword ->
            keyword.isNotBlank() && screenTexts.any { it.equals(keyword, ignoreCase = true) }
        }
}
