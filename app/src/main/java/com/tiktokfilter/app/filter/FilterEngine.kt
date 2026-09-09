package com.tiktokfilter.app.filter

enum class SkipReason { AD, BLOCKED_CREATOR, REPEAT_VIEW }

/** [detail] is the creator handle for BLOCKED_CREATOR, the matched keyword for AD, or
  * "N views" for REPEAT_VIEW - all three go straight into the activity log so a skip is
  * explainable after the fact. */
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

    // Known template shapes [videoFingerprint] excludes when guessing at the video's own
    // caption text - confirmed real wording from diagnostic logs / test fixtures ("Like
    // video 13.7K likes", "128 comments"), not a caption itself.
    private val likeCountRegex = Regex("^Like video .+ likes?$", RegexOption.IGNORE_CASE)
    private val commentCountRegex = Regex("^[\\d,.]+[A-Za-z]* comments?$", RegexOption.IGNORE_CASE)

    // UNCONFIRMED, reasonable-sounding guess (same honesty status as every other
    // threshold in this app) - short enough to still catch a real short caption, long
    // enough to filter out a single emoji/generic reaction that could plausibly repeat
    // across many different, unrelated videos and collapse their fingerprints together
    // (see videoFingerprint's own "CONFIRMED REAL BUG" comment).
    private const val MIN_CAPTION_PROXY_LENGTH = 8

    fun evaluate(
        screenTexts: List<String>,
        adKeywordsEnabled: Boolean,
        adKeywords: List<String>,
        blockedCreatorsEnabled: Boolean,
        // Expected already-normalized (lowercase, no leading '@') - see normalizeHandle.
        blockedCreators: Set<String>,
        // Repeat-view skip: the caller (TikTokFilterService) looks up how many times
        // [videoFingerprint] has already recorded a genuine (non-skipped) viewing of this
        // exact video, via RepeatViewRepository, and passes it in here - this function
        // stays pure/testable rather than reaching into SharedPreferences itself.
        // Defaulted so every existing caller/test compiles unchanged.
        repeatViewSkipEnabled: Boolean = false,
        repeatViewCount: Int = 0,
        repeatViewLimit: Int = 3
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
                keyword.isNotBlank() && visibleVideoTexts.any { containsWholeWord(it, keyword) }
            }
            if (matchedKeyword != null) {
                return SkipDecision(SkipReason.AD, matchedKeyword)
            }
        }
        // Checked last: [videoFingerprint] is the least certain signal of the three (it
        // depends on a caption-text heuristic on top of the same creator-identity lookup
        // ad/blocked-creator already rely on), so it only gets a say once neither of the
        // more specific reasons already decided this video.
        if (repeatViewSkipEnabled && repeatViewCount >= repeatViewLimit) {
            return SkipDecision(SkipReason.REPEAT_VIEW, "$repeatViewCount views")
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
            visibleVideoTexts.any { containsWholeWord(it, subject) }
        }
    }

    /** Whole-word/whole-phrase match: true when [keyword] appears in [text] as its own
      * token, bounded by whitespace/punctuation or the start/end of [text] - not merely
      * as a substring of a longer word. CONFIRMED REAL BUG this fixes (real diagnostic
      * log, docs/feed_screen_gate/PRD.md): a driver's configured Ad Keyword "Ad" matched
      * as a plain substring inside TikTok's own ubiquitous "Add or remove this video from
      * Favourites." chrome text (present on nearly every video, ad or not), and inside
      * "Add comment..." (the comments-panel input placeholder) and "Podcast Addict" (an
      * unrelated app name on the Android Recents screen) - meaning the keyword fired on
      * ordinary videos, an open comments panel, and even a non-TikTok screen, not just
      * real ads. Case-insensitive, matching every other keyword check in this file.
      *
      * `internal`, not `private`: also reused by TikTokActionCoordinator.findAndClickNode,
      * which had the identical plain-substring bug for Block/Download menu button
      * matching - same root cause, same fix, one shared implementation rather than two
      * copies that could drift.
      *
      * A [keyword] with no letters/digits at all (e.g. the real default live-room "..."
      * more-options button) is matched as a plain substring instead: `\b` only matches
      * at a transition between a word character and a non-word character, so a keyword
      * made entirely of punctuation has no well-defined word boundary to require in the
      * first place - wrapping it in `\b...\b` would silently never match anything,
      * turning "narrower" into "completely broken" for exactly this shape of keyword.
      * Falls back to a plain substring check if [keyword] can't compile as a regex too
      * (Regex.escape prevents that in practice, but a driver-entered keyword is untrusted
      * input, not something to trust blindly) - never crash on it, same defensive stance
      * this app already takes toward TikTok's own screen text. */
    internal fun containsWholeWord(text: String, keyword: String): Boolean {
        if (keyword.none { it.isLetterOrDigit() }) {
            return text.contains(keyword, ignoreCase = true)
        }
        return try {
            Regex("(?i)\\b${Regex.escape(keyword)}\\b").containsMatchIn(text)
        } catch (e: Exception) {
            text.contains(keyword, ignoreCase = true)
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

    /** Stable-enough "is this the same video/screen as last time" identity for
      * TikTokFilterService's skip and Subject-Boost-auto-like duplicate-action guards.
      * Prefers the real creator identity ([extractHandle]) exactly as before - no change
      * when that succeeds. When it fails (TikTok didn't render a "<name> profile" node
      * this read), the OLD fallback used here was `texts.firstOrNull()` - whatever string
      * happened to be first in a full-screen traversal. That single field is not
      * guaranteed stable across two reads of the exact same still-on-screen video (a
      * re-layout, a lazily-populated chrome element, anything that isn't the video
      * itself changing) - CONFIRMED REAL BUG this fixes: an unstable fallback defeats the
      * "still transitioning, don't skip again" duplicate guard, letting the SAME stuck
      * video (performSkipGesture is a fire-and-forget dispatchGesture call, never
      * confirmed to have actually advanced TikTok) get re-skipped repeatedly - rapid,
      * uncontrollable-looking swiping on what may be ONE video, not evidence of many
      * different real matches. This also resolves docs/PRD.md ss4a-P3's OPPOSITE-direction
      * concern (a fallback that's wrongly STABLE across two different videos, silently
      * suppressing a real skip) better than that PRD's own "drop the fallback" suggestion
      * would have - dropping it entirely would have made THIS bug worse, not better.
      * Uses the full current-video-scoped text (already-existing [currentVideoTexts],
      * same scoping [evaluate]/[videoFingerprint] use) rather than one arbitrary field -
      * far less likely to coincidentally collide between two different videos, and
      * stable across re-reads of an unchanged screen. Filters out the same known
      * template shapes [videoFingerprint] does ([isKnownTemplateText], no handle to
      * compare against here since [extractHandle] already failed) - not just
      * like/comment counts - so two different, genuinely captionless videos don't
      * collapse to the same identity just because their only remaining scoped text is a
      * generic marker like "Video" - the same class of collision
      * [videoFingerprint]'s own "CONFIRMED REAL BUG" doc already covers, closed here
      * too rather than partially. */
    fun videoIdentity(screenTexts: List<String>): String? {
        extractHandle(screenTexts)?.let { return it }
        val scoped = currentVideoTexts(screenTexts)
            .filterNot { isKnownTemplateText(it, handle = null) }
        if (scoped.isEmpty()) return null
        return scoped.joinToString("|")
    }

    /** Best-effort per-VIDEO fingerprint for repeat-view tracking (see
      * RepeatViewRepository) - unlike [extractHandle], which only identifies the creator
      * (the same for every video they've ever posted), this needs to tell two DIFFERENT
      * videos from the same creator apart too. Combines the creator identity with a proxy
      * for the video's own caption: the longest remaining text in the current video's
      * scoped block that doesn't match one of TikTok's known template shapes (a profile/
      * follow node, a like/comment count, the literal "Video" marker, or the creator's own
      * name/handle repeated standalone - all confirmed template patterns, not a real
      * caption, from the same real diagnostic logs [profileContentDescriptionRegex] is
      * based on). Longest-string-wins is a heuristic, not a certainty - no field TikTok
      * exposes is documented as "the caption, guaranteed" - but it's the same kind of
      * best-effort signal every other heuristic in this file already is.
      *
      * Returns null if no creator identity can be found at all ([extractHandle] returns
      * null), OR if no caption-proxy text meeting [MIN_CAPTION_PROXY_LENGTH] can be found
      * for this video - deliberately NOT falling back to some other value the way
      * TikTokFilterService's transient same-tick dedup does (see the app's own premortem,
      * docs/PRD.md ss4a-P3): a wrong fingerprint here would corrupt a PERSISTED count
      * across the user's whole history, not just risk one duplicate action in the moment.
      *
      * CONFIRMED REAL BUG, fixed here: the caption-proxy check originally fell back to an
      * empty string when no distinguishing text was found (a captionless video, or one
      * whose caption is short/generic) - collapsing the fingerprint to just "handle|" for
      * EVERY such video from that creator. Scrolling past a handful of unrelated,
      * genuinely-different captionless videos from the same creator hit the repeat-view
      * limit on their shared fingerprint almost immediately, then auto-skipped every
      * subsequent video matching that same degenerate fingerprint - continuous,
      * unstoppable scrolling, the exact same failure signature this codebase already
      * documented once before for the old Subject Filter ("swipe through video after
      * video with no visible stopping point"). Refusing to track a video with no real
      * caption-proxy text at all closes that collision, at the cost of never tracking
      * repeat views for genuinely captionless content - a real, accepted tradeoff, not a
      * free fix. */
    fun videoFingerprint(screenTexts: List<String>): String? {
        val handle = extractHandle(screenTexts) ?: return null
        val visibleVideoTexts = currentVideoTexts(screenTexts)
        val captionProxy = visibleVideoTexts
            .filterNot { isKnownTemplateText(it, handle) }
            .maxByOrNull { it.length }
            ?: return null
        if (captionProxy.trim().length < MIN_CAPTION_PROXY_LENGTH) return null
        return "$handle|$captionProxy"
    }

    /** [handle] is null when there's no real creator identity to compare against (see
      * [videoIdentity], called when [extractHandle] already failed) - every other check
      * here is independent of the handle anyway, so this still filters out the rest of
      * TikTok's known chrome/template text without one. */
    private fun isKnownTemplateText(text: String, handle: String?): Boolean {
        val trimmed = text.trim()
        return (handle != null && trimmed.equals(handle.trim(), ignoreCase = true)) ||
            profileContentDescriptionRegex.matches(trimmed) ||
            trimmed.startsWith("Follow ", ignoreCase = true) ||
            handleRegex.matches(trimmed) ||
            likeCountRegex.matches(trimmed) ||
            commentCountRegex.matches(trimmed) ||
            trimmed.equals("Video", ignoreCase = true)
    }

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
