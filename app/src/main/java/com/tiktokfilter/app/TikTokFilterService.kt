package com.tiktokfilter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokfilter.app.diagnostics.DiagnosticLog
import com.tiktokfilter.app.filter.FilterEngine
import com.tiktokfilter.app.overlay.OverlayController
import com.tiktokfilter.app.tiktokactions.DownloadMode
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
    // Identifies whichever video the last skip acted on (see performSkipGesture's call
    // site) - guards against skipping the same video more than once if TikTok's own
    // transition to the next video takes longer than COOLDOWN_MILLIS, which the
    // time-only cooldown alone can't detect.
    private var lastSkippedVideoIdentity: String? = null
    // Same dedup shape as lastSkippedVideoIdentity, but for Subject Boost's auto-like -
    // without it, a video that lingers on screen across multiple accessibility events
    // (normal - nothing here forces it to move on) would get an attempted like on every
    // single one of those events, not just once.
    private var lastAutoLikedVideoIdentity: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // A single reusable Runnable so scheduling it again (or cancelling it) always
    // targets every currently-pending instance - see the debounce comment below.
    private val hideOverlayRunnable = Runnable { overlayController.hide() }

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
            onDownloadVideoTapped = { handleOverlayDownloadTapped(DownloadMode.VIDEO_ONLY) },
            onDownloadAudioTapped = { handleOverlayDownloadTapped(DownloadMode.AUDIO_ONLY) }
        )
        diagnosticLog.log("SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        // The overlay's own buttons are drawn in a window that belongs to this app, so
        // interacting with them (or the window just redrawing) can itself generate an
        // accessibility event tagged with this app's package - reacting to that as "the
        // user left TikTok" would hide the overlay out from under the tap that's still
        // landing on it. Never a real signal either way, so just ignore it.
        if (packageName == this.packageName) return

        if (packageName !in settingsRepository.targetPackages) {
            // Don't hide the instant a single event from some other package shows up -
            // system UI, a keyboard, a notification icon updating, etc. can all fire one
            // while TikTok is still genuinely in front. A real switch away from TikTok is
            // followed by silence from TikTok's own events, so a short delayed hide -
            // cancelled below the moment a TikTok event arrives - tells the two apart
            // without visibly flashing the overlay for every unrelated event in between.
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MILLIS)
            return
        }
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (settingsRepository.isOverlayEnabled) overlayController.show() else overlayController.hide()

        val root = rootInActiveWindow ?: return

        // In-flight Block/Download automations advance independent of the skip cooldown
        // below - they're triggered by a deliberate tap, not a per-video reaction, and
        // have their own timeout (see TikTokActionCoordinator).
        actionCoordinator.onScreenUpdated(root)

        // Auto-skip (ad/blocked-creator) must never fire while a Block/Download
        // tap sequence is still working on the current video - swiping away mid-sequence
        // would pull the video out from under it, which can make a multi-tap automation
        // fail outright or, worse, land its next tap on whatever video auto-skip moved to
        // instead. onScreenUpdated above may have just completed the pending sequence on
        // this very event, in which case hasPendingAction is already false again and
        // auto-skip resumes normally starting next event - this only pauses it mid-flight.
        if (actionCoordinator.hasPendingAction) {
            @Suppress("DEPRECATION")
            root.recycle()
            diagnosticLog.log("FILTER", "auto-skip paused - Block/Download automation in progress")
            return
        }

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

        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)

        val decision = FilterEngine.evaluate(
            screenTexts = texts,
            adKeywordsEnabled = settingsRepository.isAdSkipEnabled,
            adKeywords = settingsRepository.adKeywords,
            blockedCreatorsEnabled = settingsRepository.isBlockedCreatorSkipEnabled,
            blockedCreators = settingsRepository.blockedCreators.toSet()
        )
        if (decision == null) {
            diagnosticLog.log("FILTER", "no match - live=$isLive - texts=$texts")
            // Subject Boost never skips - it only ever adds a positive signal (auto-like)
            // on top of otherwise-normal browsing, so it's only relevant once we already
            // know this video isn't being skipped for an unrelated reason (ad/blocked
            // creator) above. root is still alive here (not yet recycled) since the
            // auto-like tap needs it, unlike the skip path below which never touches root
            // again once it's decided to skip.
            attemptSubjectBoost(root, texts, isLive)
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }
        @Suppress("DEPRECATION")
        root.recycle()
        // A Live room is a different kind of screen than a normal video - swiping away
        // from one is gated by its own toggle (default on) rather than assuming the
        // same behavior as skipping a video is always wanted here too.
        if (isLive && !settingsRepository.isLiveStreamSkipEnabled) {
            diagnosticLog.log("FILTER", "${decision.reason} matched \"${decision.detail}\" on a Live stream but live-skip is disabled - texts=$texts")
            return
        }
        // A best-effort "which video is this" fingerprint - the creator's display name
        // when one can be found, same identity FilterEngine.extractHandle already relies
        // on elsewhere. If TikTok is still transitioning out the video we just skipped,
        // this will still read as that same video rather than the next one, and skipping
        // it again would be a duplicate, not a new decision.
        val videoIdentity = FilterEngine.extractHandle(texts) ?: texts.firstOrNull()
        if (videoIdentity != null && videoIdentity == lastSkippedVideoIdentity) {
            diagnosticLog.log("FILTER", "duplicate skip suppressed for the same video (still transitioning?) - texts=$texts")
            return
        }
        diagnosticLog.log("FILTER", "${decision.reason} matched \"${decision.detail}\" - live=$isLive - texts=$texts")

        lastSkipMillis = now
        lastSkippedVideoIdentity = videoIdentity
        statsRepository.recordSkip(decision)
        performSkipGesture()
    }

    /** Subject Boost: if enabled and the current video's on-screen text matches a
      * configured subject, auto-likes it as a positive engagement signal - see
      * SettingsRepository.isSubjectBoostEnabled's doc for why this replaced the old
      * force-skip Subject Filter. Skipped entirely on a Live room: its layout differs
      * enough from a normal video that the same Like-button keyword search is more
      * likely to mis-tap something else, and Live rooms don't have the same caption/
      * hashtag text a subject match would normally be based on anyway. */
    private fun attemptSubjectBoost(root: AccessibilityNodeInfo, texts: List<String>, isLive: Boolean) {
        if (isLive || !settingsRepository.isSubjectBoostEnabled) return
        if (!FilterEngine.matchesSubject(texts, settingsRepository.subjectKeywords)) return
        val videoIdentity = FilterEngine.extractHandle(texts) ?: texts.firstOrNull()
        if (videoIdentity != null && videoIdentity == lastAutoLikedVideoIdentity) return
        lastAutoLikedVideoIdentity = videoIdentity
        diagnosticLog.log("SUBJECT_BOOST", "subject match - attempting auto-like - texts=$texts")
        actionCoordinator.attemptLikeCurrentVideo(root)
    }

    override fun onInterrupt() {
        diagnosticLog.log("SERVICE", "onInterrupt")
        mainHandler.removeCallbacks(hideOverlayRunnable)
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
        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)
        diagnosticLog.log("OVERLAY", "Block tapped for $handle (live=$isLive)")
        actionCoordinator.startBlockCurrentCreator(handle, isLive)
    }

    /** Same fresh-snapshot approach as [handleOverlayBlockTapped] - Download needs to know
      * whether the current screen is a Live room before starting the tap sequence, since
      * there's no video file to save from a live broadcast (see
      * TikTokActionCoordinator.startDownloadCurrentVideo). [mode] reflects which of the
      * overlay's Video/Audio choice buttons was tapped. */
    private fun handleOverlayDownloadTapped(mode: DownloadMode) {
        val root = rootInActiveWindow
        if (root == null) {
            statsRepository.recordEvent("Download tapped but no screen content was available")
            diagnosticLog.log("OVERLAY", "Download tapped, rootInActiveWindow was null")
            return
        }
        val texts = mutableListOf<String>()
        collectText(root, texts)
        @Suppress("DEPRECATION")
        root.recycle()

        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)
        diagnosticLog.log("OVERLAY", "Download tapped, mode=$mode (live=$isLive)")
        actionCoordinator.startDownloadCurrentVideo(mode, isLive)
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
        private const val OVERLAY_HIDE_DELAY_MILLIS = 800L
    }
}
