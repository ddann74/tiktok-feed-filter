package com.tiktokfilter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokfilter.app.filter.FilterEngine

/**
 * Reads whatever text TikTok is currently rendering (via the accessibility tree) and, if
 * it looks like an ad or a blocked creator, dispatches a swipe-up gesture to skip past it -
 * the same mechanism a real finger swipe uses, since there's no official API for either
 * "is this an ad" or "skip this video". This only ever acts on the configured target
 * package(s) and never reads or acts on anything outside them.
 */
class TikTokFilterService : AccessibilityService() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private var lastSkipMillis: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in settingsRepository.targetPackages) return

        // Right after a skip, TikTok is still loading/animating in the next video - reading
        // the screen during that window would evaluate a half-rendered video (or the one we
        // just skipped past) and could trigger a second, unwanted skip.
        val now = System.currentTimeMillis()
        if (now - lastSkipMillis < COOLDOWN_MILLIS) return

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectText(root, texts)
        @Suppress("DEPRECATION")
        root.recycle()

        val decision = FilterEngine.evaluate(
            screenTexts = texts,
            adKeywordsEnabled = settingsRepository.isAdSkipEnabled,
            adKeywords = settingsRepository.adKeywords,
            blockedCreatorsEnabled = settingsRepository.isBlockedCreatorSkipEnabled,
            blockedCreators = settingsRepository.blockedCreators.toSet()
        ) ?: return

        lastSkipMillis = now
        statsRepository.recordSkip(decision)
        performSkipGesture()
    }

    override fun onInterrupt() {}

    /** Depth-first collection of every text/contentDescription string in the current
      * window - the closest available substitute for "what does this screen say",
      * since accessibility nodes don't expose anything richer than that. */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, out, depth + 1)
            @Suppress("DEPRECATION")
            child?.recycle()
        }
    }

    /** A swipe from just below center to just above it, matching how TikTok itself expects
      * a "next video" gesture - vertical, roughly half the screen's height, quick. */
    private fun performSkipGesture() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MILLIS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val COOLDOWN_MILLIS = 900L
        private const val SWIPE_DURATION_MILLIS = 250L
        private const val MAX_TREE_DEPTH = 60
    }
}
