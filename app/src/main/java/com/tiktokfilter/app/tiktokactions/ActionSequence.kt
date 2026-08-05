package com.tiktokfilter.app.tiktokactions

/**
 * Pure step-advancement logic for a multi-tap TikTok automation - e.g. "open the ...
 * menu, then tap Block, then tap the confirm dialog's Block button". No Android
 * dependencies, so it's directly unit-testable; TikTokFilterService is responsible for
 * actually searching the accessibility tree for a node matching [currentStageKeywords]
 * and tapping it, then calling [advance] or [hasTimedOut] with the result.
 *
 * Each element of [stages] is the set of candidate keywords for that step - a set
 * rather than one exact string because TikTok's exact button wording isn't something
 * that can be verified without a real device, so several plausible candidates are
 * tried together (case-insensitive substring match against on-screen text).
 */
data class ActionSequence(
    val stages: List<List<String>>,
    val startedAtMillis: Long,
    val stageIndex: Int = 0
) {
    val isComplete: Boolean get() = stageIndex >= stages.size

    val currentStageKeywords: List<String> get() = stages.getOrElse(stageIndex) { emptyList() }

    fun advance(): ActionSequence = copy(stageIndex = stageIndex + 1)

    fun hasTimedOut(nowMillis: Long, timeoutMillis: Long): Boolean =
        nowMillis - startedAtMillis > timeoutMillis
}
