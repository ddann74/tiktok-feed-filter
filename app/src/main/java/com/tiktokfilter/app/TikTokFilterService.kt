package com.tiktokfilter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokfilter.app.diagnostics.DiagnosticLog
import com.tiktokfilter.app.filter.FilterEngine
import com.tiktokfilter.app.overlay.OverlayController
import com.tiktokfilter.app.tiktokactions.TikTokActionCoordinator

/**
 * Reads whatever text TikTok is currently rendering (via the accessibility tree) and, if
 * it looks like an ad or a blocked creator, dispatches a swipe-up gesture to skip past it -
 * the same mechanism a real finger swipe uses, since there's no official API for either
 * "is this an ad" or "skip this video". Also shows the floating Block/Download buttons
 * while TikTok is in front, and drives whichever multi-tap TikTok automation (real Block,
 * Download) is currently in flight via [TikTokActionCoordinator]. This only ever acts on
 * the configured target package(s) and never reads or acts on anything outside them.
 */
class TikTokFilterService : AccessibilityService() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var diagnosticLog: DiagnosticLog
    private lateinit var actionCoordinator: TikTokActionCoordinator
    private lateinit var overlayController: OverlayController
    private var lastSkipMillis: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)
        actionCoordinator = TikTokActionCoordinator(this, settingsRepository, statsRepository, diagnosticLog)
        overlayController = OverlayController(
            service = this,
            diagnosticLog = diagnosticLog,
            onBlockTapped = { handleOverlayBlockTapped() },
            onDownloadTapped = { actionCoordinator.startDownloadCurrentVideo() }
        )
        diagnosticLog.log("SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in settingsRepository.targetPackages) {
            overlayController.hide()
            return
        }
        if (settingsRepository.isOverlayEnabled) overlayController.show() else overlayController.hide()

        val root = rootInActiveWindow ?: return

        // In-flight Block/Download automations advance independent of the skip cooldown
        // below - they're triggered by a deliberate tap, not a per-video reaction, and
        // have their own timeout (see TikTokActionCoordinator).
        actionCoordinator.onScreenUpdated(root)

        // Right after a skip, TikTok is still loading/animating in the next video - reading
        // the screen during that window would evaluate a half-rendered video (or the one we
        // just skipped past) and could trigger a second, unwanted skip.
        val now = System.currentTimeMillis()
        if (now - lastSkipMillis < COOLDOWN_MILLIS) {
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }

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
        )
        if (decision == null) {
            diagnosticLog.log("FILTER", "no match - texts=$texts")
            return
        }
        diagnosticLog.log("FILTER", "${decision.reason} matched \"${decision.detail}\" - texts=$texts")

        lastSkipMillis = now
        statsRepository.recordSkip(decision)
        performSkipGesture()
    }

    override fun onInterrupt() {
        diagnosticLog.log("SERVICE", "onInterrupt")
        overlayController.hide()
    }

    /** The overlay button click arrives outside the normal event flow, so this reads a
      * fresh snapshot of the current screen itself rather than relying on state left
      * over from the last onAccessibilityEvent call. */
    private fun handleOverlayBlockTapped() {
        val root = rootInActiveWindow
        if (root == null) {
            statsRepository.recordEvent("Block tapped but no screen content was available")
            diagnosticLog.log("OVERLAY", "Block tapped, rootInActiveWindow was null")
            return
        }
        val texts = mutableListOf<String>()
        collectText(root, texts)
        @Suppress("DEPRECATION")
        root.recycle()

        val handle = FilterEngine.extractHandle(texts)
        if (handle == null) {
            statsRepository.recordEvent("Block tapped but couldn't identify the current creator's handle")
            diagnosticLog.log("OVERLAY", "Block tapped, no handle found - texts=$texts")
            return
        }
        diagnosticLog.log("OVERLAY", "Block tapped for $handle")
        actionCoordinator.startBlockCurrentCreator(handle)
    }

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
