package com.tiktokfilter.app.filter

enum class SkipReason { AD, BLOCKED_CREATOR }

/** [detail] is the creator handle for BLOCKED_CREATOR, or the matched keyword for AD -
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
 * project README for how to notice and fix that without a rebuild.
 */
object FilterEngine {

    // TikTok renders the current video's creator as its own text node reading exactly
    // "@handle" (not embedded in a longer sentence) - this matches that shape specifically,
    // rather than any "@word" appearing inside a caption or comment.
    private val handleRegex = Regex("^@[A-Za-z0-9_.]{1,40}$")

    fun evaluate(
        screenTexts: List<String>,
        adKeywordsEnabled: Boolean,
        adKeywords: List<String>,
        blockedCreatorsEnabled: Boolean,
        // Expected already-normalized (lowercase, no leading '@') - see normalizeHandle.
        blockedCreators: Set<String>
    ): SkipDecision? {
        // Blocked-creator match is checked first: it's a specific, near-exact signal,
        // whereas ad-keyword matching is a plain substring search and more prone to a
        // false positive (e.g. a caption that happens to mention "sponsored").
        if (blockedCreatorsEnabled) {
            val handle = extractHandle(screenTexts)
            if (handle != null && blockedCreators.contains(normalizeHandle(handle))) {
                return SkipDecision(SkipReason.BLOCKED_CREATOR, handle)
            }
        }
        if (adKeywordsEnabled) {
            val matchedKeyword = adKeywords.firstOrNull { keyword ->
                keyword.isNotBlank() && screenTexts.any { it.contains(keyword, ignoreCase = true) }
            }
            if (matchedKeyword != null) {
                return SkipDecision(SkipReason.AD, matchedKeyword)
            }
        }
        return null
    }

    fun extractHandle(screenTexts: List<String>): String? =
        screenTexts.firstOrNull { handleRegex.matches(it.trim()) }

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
