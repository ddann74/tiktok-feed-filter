package com.tiktokfilter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokfilter.app.diagnostics.DiagnosticLog
import com.tiktokfilter.app.filter.FilterEngine
import com.tiktokfilter.app.filter.HardStopGuard
import com.tiktokfilter.app.filter.RepeatViewRepository
import com.tiktokfilter.app.filter.SkipReason
import com.tiktokfilter.app.filter.SkipStreakGuard
import com.tiktokfilter.app.filter.SkipStreakState
import com.tiktokfilter.app.filter.TripHistoryState
import com.tiktokfilter.app.filter.VideoCategory
import com.tiktokfilter.app.filter.VideoWatchTracker
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
    private lateinit var repeatViewRepository: RepeatViewRepository
    private var lastSkipMillis: Long = 0L
    // Identifies whichever video the last skip acted on (see performSkipGesture's call
    // site) - guards against skipping the same video more than once if TikTok's own
    // transition to the next video takes longer than COOLDOWN_MILLIS, which the
    // time-only cooldown alone can't detect.
    private var lastSkippedVideoIdentity: String? = null
    // CONFIRMED REAL GAP, found auditing the diagnostic log's own coverage: the
    // duplicate-skip guard above records that a skip was ATTEMPTED, never whether TikTok
    // actually advanced past it - a real diagnostic log showed the same video stuck for
    // 1m28s, producing ~290 "duplicate skip suppressed" lines with nothing ever saying
    // the original skip didn't take effect (docs/feed_screen_gate/PRD.md ss7.3's own
    // premortem P3 flagged this as known-but-unfixed at the time). Tracks which stuck
    // video has already gotten its one-time warning, so it fires once per stuck episode
    // - not every ~300ms for as long as the video stays stuck - see the duplicate-skip
    // branch below.
    private var stuckVideoWarningLoggedForIdentity: String? = null
    // Same dedup shape as lastSkippedVideoIdentity, but for Subject Boost's auto-like -
    // without it, a video that lingers on screen across multiple accessibility events
    // (normal - nothing here forces it to move on) would get an attempted like on every
    // single one of those events, not just once.
    private var lastAutoLikedVideoIdentity: String? = null
    // Same dedup shape again, but for repeat-view counting - without it, a video that
    // lingers on screen across multiple NOT-skipped accessibility events (normal - the
    // user is just watching it) would count as several "views" instead of one. Tracks the
    // more precise FilterEngine.videoFingerprint (creator + caption proxy), not the
    // coarser lastSkippedVideoIdentity/lastAutoLikedVideoIdentity fallback shape, since a
    // wrong repeat-view count would be a PERSISTED mistake, not just a one-off in-the-
    // moment one (see RepeatViewRepository's own doc).
    private var lastSeenVideoFingerprint: String? = null
    // Runaway auto-skip circuit breaker - see SkipStreakGuard's own doc. Reset to a fresh
    // streak on any genuine (non-skipped) view, since that's real evidence browsing is
    // actually progressing normally, not stuck in a skip loop.
    private var skipStreakState = SkipStreakState()
    private var circuitBreakerTrippedUntilMillis = 0L
    // Escalation on top of the circuit breaker above - see HardStopGuard's own doc and
    // docs/auto_scroll_hard_stop/PRD.md. A pause that repeats forever isn't "stopped",
    // which is what was actually reported after the circuit breaker alone shipped.
    private var tripHistoryState = TripHistoryState()
    // Per-video category (Ad/BlockedCreator/RepeatView/Post/Unidentified) + watch-
    // duration tracking - docs/video_category_watch_tracking/PRD.md. In-memory only,
    // same as every other piece of state in this file (PRD §5's own leaning) - an
    // accessibility service restarting mid-video already loses its own book-keeping for
    // every other feature here, this isn't a new category of limitation.
    private val videoWatchTracker = VideoWatchTracker(MAX_TRACKED_WATCH_DURATION_MILLIS)

    private val mainHandler = Handler(Looper.getMainLooper())
    // A single reusable Runnable so scheduling it again (or cancelling it) always
    // targets every currently-pending instance - see the debounce comment below.
    private val hideOverlayRunnable = Runnable { overlayController.hide() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)
        repeatViewRepository = RepeatViewRepository(this)
        actionCoordinator = TikTokActionCoordinator(this, settingsRepository, statsRepository, diagnosticLog)
        overlayController = OverlayController(
            service = this,
            diagnosticLog = diagnosticLog,
            onBlockTapped = { handleOverlayBlockTapped() },
            onDownloadVideoTapped = { handleOverlayDownloadTapped(DownloadMode.VIDEO_ONLY) },
            onDownloadAudioTapped = { handleOverlayDownloadTapped(DownloadMode.AUDIO_ONLY) }
        )
        // Found auditing the diagnostic log's own coverage: nothing anywhere recorded
        // which device this is, so an OEM-specific bug (a manufacturer's own aggressive
        // battery manager, a launcher/gesture-nav quirk like the still-unconfirmed
        // Recents-screen question in docs/feed_screen_gate/PRD.md ss5) would have no way
        // to be told apart from a universal one just by reading the log. Once per
        // session, matching the same reasoning dasher-monitor-'s own
        // docs/watchdog_reliability/PRD.md already established for this exact gap.
        diagnosticLog.log(
            "SERVICE",
            "device: manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}"
        )
        diagnosticLog.log("SERVICE", "onServiceConnected")
    }

    /** AccessibilityService's own unbind hook - fires when the system disconnects this
      * service (the driver turned the accessibility permission off, or the OS/an
      * aggressive OEM battery manager tore it down). Previously silent - monitoring
      * would just stop with nothing in the log explaining why, the exact class of gap
      * dasher-monitor-'s own docs/watchdog_reliability/PRD.md already found and fixed
      * for its own accessibility service this same session. */
    override fun onUnbind(intent: Intent?): Boolean {
        diagnosticLog.log("SERVICE", "onUnbind - accessibility service disconnected")
        mainHandler.removeCallbacks(hideOverlayRunnable)
        overlayController.hide()
        return super.onUnbind(intent)
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
        // The circuit breaker's pause, once tripped below - reads the same as the cooldown
        // above (auto-skip briefly does nothing) but for a much longer window, and only
        // after a genuinely excessive streak, not every normal skip.
        if (now < circuitBreakerTrippedUntilMillis) {
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }

        val texts = mutableListOf<String>()
        collectText(root, texts)

        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)

        // Unconditional, per-event identity computation for the NEW per-video category/
        // watch-duration tracker (docs/video_category_watch_tracking/PRD.md §1/§3) -
        // deliberately a SEPARATE call from the one the existing skip-dedup guard uses
        // further down (lastSkippedVideoIdentity), so this feature can't change that
        // guard's own behavior even if this call's timing ever needs to diverge.
        val trackedVideoIdentity = FilterEngine.videoIdentity(texts)

        // Repeat-view skip: excluded on a Live room the same way Subject Boost/Download
        // already are - a live broadcast isn't a repeatable "video" the way a normal FYP
        // post is, and a Live room's constantly-changing viewer count/comments would make
        // FilterEngine.videoFingerprint's caption-proxy heuristic especially unstable.
        // videoFingerprint returns null when no creator identity can be found at all (see
        // its own doc) - repeatViewCount stays 0 in that case, same as "never seen before",
        // rather than guessing at some other identity.
        val videoFingerprint = if (isLive) null else FilterEngine.videoFingerprint(texts)
        val repeatViewCount = if (videoFingerprint != null) repeatViewRepository.viewCount(videoFingerprint) else 0

        val decision = FilterEngine.evaluate(
            screenTexts = texts,
            adKeywordsEnabled = settingsRepository.isAdSkipEnabled,
            adKeywords = settingsRepository.adKeywords,
            blockedCreatorsEnabled = settingsRepository.isBlockedCreatorSkipEnabled,
            blockedCreators = settingsRepository.blockedCreators.toSet(),
            repeatViewSkipEnabled = settingsRepository.isRepeatViewSkipEnabled,
            repeatViewCount = repeatViewCount,
            repeatViewLimit = settingsRepository.repeatViewLimit
        )

        // Category classification (PRD §1's five-way mapping) + the tracker call itself,
        // UNCONDITIONAL and FIRST, before the skip/no-match branches below - this is what
        // makes videoWatchTracker.currentElapsedMillis(now) safe to call later in the
        // decision != null branch (the tracker's state already reflects THIS video/event).
        val category = when (decision?.reason) {
            SkipReason.AD -> VideoCategory.AD
            SkipReason.BLOCKED_CREATOR -> VideoCategory.BLOCKED_CREATOR
            SkipReason.REPEAT_VIEW -> VideoCategory.REPEAT_VIEW
            null -> if (trackedVideoIdentity == null) VideoCategory.UNIDENTIFIED else VideoCategory.POST
        }
        val finishedWatch = videoWatchTracker.onScreenRead(trackedVideoIdentity, category, now)
        if (finishedWatch != null) {
            when (finishedWatch.category) {
                // Ad/BlockedCreator/RepeatView already got their own enriched skip line
                // at the exact moment they were skipped (see currentElapsedMillis below,
                // in the decision != null branch) - a FinishedWatch for one of these
                // here, reported later on the NEXT video's transition, would just be a
                // stale duplicate of that same skip. No-op by design (§3), not an
                // oversight.
                VideoCategory.AD, VideoCategory.BLOCKED_CREATOR, VideoCategory.REPEAT_VIEW -> {}
                VideoCategory.UNIDENTIFIED ->
                    statsRepository.recordUnidentifiedWatch(finishedWatch.durationMillis)
                VideoCategory.POST ->
                    // §2.1/P3: only log a Post once its capped duration crosses the
                    // "was this actually watched" threshold - avoids flooding the
                    // 50-entry Activity log cap with every single video scrolled past.
                    if (finishedWatch.durationMillis >= POST_WATCH_MIN_MILLIS) {
                        statsRepository.recordPostWatch(finishedWatch.durationMillis)
                    }
            }
        }

        if (decision == null) {
            // A genuine, non-skipped view - real evidence browsing is progressing
            // normally, not stuck in a skip loop, so any in-progress skip streak is stale.
            skipStreakState = SkipStreakState()
            diagnosticLog.log("FILTER", "no match - live=$isLive - texts=$texts")
            // Counts as one genuine view - see lastSeenVideoFingerprint's own field
            // comment for why this only fires once per real transition into this video,
            // not once per lingering accessibility event. A video that got skipped for
            // any reason (ad, blocked creator, already over the repeat-view limit) was
            // never actually watched, so it's never counted here.
            if (videoFingerprint != null && videoFingerprint != lastSeenVideoFingerprint) {
                repeatViewRepository.recordView(videoFingerprint)
                lastSeenVideoFingerprint = videoFingerprint
                diagnosticLog.log("FILTER", "view recorded (now $repeatViewCount -> ${repeatViewCount + 1}) - texts=$texts")
            }
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
        // CONFIRMED REAL GAP, escalated to an actual gate here (docs/feed_screen_gate/
        // PRD.md ss11): TWO separate real diagnostic logs showed this exact
        // matched-and-about-to-skip path fire on screens that aren't the main TikTok
        // feed at all - the Android Recents/task-switcher screen (ss1.3), TikTok's own
        // comments panel (both logs), and TikTok's own share-to bottom sheet (ss11) -
        // directly matching driver-reported "auto scroll ... in the comments ...
        // outside the app" symptoms. Was diagnostic-only (WARNING, never blocked)
        // through PR #4; see FilterEngine.looksLikeFeedScreen's own doc for why it's
        // now safe to actually suppress the skip instead. Live rooms are unaffected -
        // already excluded above, before this line is ever reached.
        if (!isLive && !FilterEngine.looksLikeFeedScreen(texts)) {
            diagnosticLog.log("FILTER", "${decision.reason} matched \"${decision.detail}\" on a screen that doesn't look like the main TikTok feed (no \"For You\" tab visible) - SKIP SUPPRESSED - texts=$texts")
            return
        }
        // A best-effort "which video is this" identity - see FilterEngine.videoIdentity's
        // own doc for why this is no longer a raw `extractHandle(...) ?: texts.firstOrNull()`
        // fallback: that pattern was CONFIRMED to let a stuck video (one that didn't
        // actually advance after performSkipGesture) get re-skipped repeatedly, which is
        // the actual mechanism behind a driver-reported "auto scrolling out of control"
        // incident - see docs/skip_dedup_root_cause/PRD.md.
        val videoIdentity = FilterEngine.videoIdentity(texts)
        if (videoIdentity != null && videoIdentity == lastSkippedVideoIdentity) {
            diagnosticLog.log("FILTER", "duplicate skip suppressed for the same video (still transitioning?) - texts=$texts")
            // One-time warning once this has gone on long enough that "still
            // transitioning" stops being a plausible explanation - UNCONFIRMED threshold
            // (STUCK_VIDEO_WARNING_MILLIS), same honesty status as every other threshold
            // in this file, but a real TikTok transition finishing in under 5s is a much
            // safer assumption than the alternative (never warning at all). Gated on
            // stuckVideoWarningLoggedForIdentity so this fires once per stuck episode,
            // not on every ~300ms re-read for as long as it stays stuck.
            if (now - lastSkipMillis >= STUCK_VIDEO_WARNING_MILLIS && videoIdentity != stuckVideoWarningLoggedForIdentity) {
                stuckVideoWarningLoggedForIdentity = videoIdentity
                val warning = "A skip was attempted ${(now - lastSkipMillis) / 1000}s ago but this " +
                    "video is still on screen - the swipe may not have actually taken effect. " +
                    "If this keeps happening, TikTok's gesture handling may need a different " +
                    "approach here, not another retry."
                statsRepository.recordEvent(warning)
                diagnosticLog.log("FILTER", "STUCK VIDEO - $warning")
            }
            return
        }
        val (updatedStreak, tripped) = SkipStreakGuard.recordSkip(
            skipStreakState, now, SKIP_STREAK_WINDOW_MILLIS, MAX_CONSECUTIVE_SKIPS
        )
        if (tripped) {
            // Whatever the actual cause turns out to be - an over-broad keyword, a
            // video-transition edge case, something not yet seen in a diagnostic log -
            // this many skips this fast isn't genuinely that many ads/blocked creators
            // back to back. Pause rather than keep swiping, and say so loudly (Activity,
            // not just Diagnostic Log) since this is exactly the "out of control" feeling
            // a silent runaway produces.
            skipStreakState = SkipStreakState()
            circuitBreakerTrippedUntilMillis = now + CIRCUIT_BREAKER_PAUSE_MILLIS
            val warning = "Auto-skip paused for ${CIRCUIT_BREAKER_PAUSE_MILLIS / 1000}s - " +
                "$MAX_CONSECUTIVE_SKIPS skips happened within ${SKIP_STREAK_WINDOW_MILLIS / 1000}s, " +
                "which looks like a runaway pattern rather than that many ads/blocked creators " +
                "genuinely back to back. Check Diagnostic Log's recent FILTER entries to see " +
                "what kept matching."
            statsRepository.recordEvent(warning)
            diagnosticLog.log("FILTER", "CIRCUIT BREAKER TRIPPED - $warning")

            // Escalation: this pause-and-resume is itself supposed to be rare. If it
            // keeps happening, the pause isn't fixing anything and "paused" isn't what
            // was actually asked for - see docs/auto_scroll_hard_stop/PRD.md.
            val (updatedTripHistory, hardStop) = HardStopGuard.recordTrip(
                tripHistoryState, now, ESCALATION_WINDOW_MILLIS, MAX_TRIPS_IN_ESCALATION_WINDOW
            )
            tripHistoryState = if (hardStop) TripHistoryState() else updatedTripHistory
            if (hardStop) {
                // A real, persisted stop - not another pause. All three toggles that can
                // actually produce a SkipDecision (see FilterEngine.evaluate/SkipReason),
                // not just ad/blocked-creator - see docs/auto_scroll_hard_stop/PRD.md
                // §3a-P2 for why disabling only two of the three would leave this
                // silently ineffective if the third is what's actually recurring.
                settingsRepository.isAdSkipEnabled = false
                settingsRepository.isBlockedCreatorSkipEnabled = false
                settingsRepository.isRepeatViewSkipEnabled = false
                val hardStopWarning = "AUTO-SKIP TURNED OFF: the pause-and-resume safety " +
                    "net above tripped $MAX_TRIPS_IN_ESCALATION_WINDOW times within " +
                    "${ESCALATION_WINDOW_MILLIS / 60_000} minutes, meaning something is " +
                    "still causing a runaway skip pattern even after pausing. Skip ads, " +
                    "Skip blocked creators, and Repeat-view skip have all been turned OFF " +
                    "- turn them back on in Filters once you've checked what's wrong. To " +
                    "help find the actual cause: make sure Diagnostic Logging is on " +
                    "(Diagnostics), reproduce this, and share the log."
                statsRepository.recordEvent(hardStopWarning)
                diagnosticLog.log("FILTER", "HARD STOP - $hardStopWarning")
            }
            return
        }
        skipStreakState = updatedStreak
        diagnosticLog.log("FILTER", "${decision.reason} matched \"${decision.detail}\" - live=$isLive - texts=$texts")

        lastSkipMillis = now
        lastSkippedVideoIdentity = videoIdentity
        // onScreenRead already ran for this exact event/video above (unconditional,
        // before this branch split) - currentElapsedMillis reads that same, already-
        // synced state, so this is this ad/blocked-creator/repeat-view's own duration,
        // not stale data left over from a previous video (PRD §3/P1, resolved).
        statsRepository.recordSkip(decision, videoWatchTracker.currentElapsedMillis(now))
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
        // See FilterEngine.videoIdentity's own doc - same fix as the skip-dedup call site,
        // same fragility this replaces.
        val videoIdentity = FilterEngine.videoIdentity(texts)
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
        // UNCONFIRMED, reasonable-sounding threshold, same honesty status as every other
        // one in this file - see stuckVideoWarningLoggedForIdentity's own field comment.
        private const val STUCK_VIDEO_WARNING_MILLIS = 5_000L
        // Runaway auto-skip circuit breaker (see SkipStreakGuard) - UNCONFIRMED, reasonable-
        // sounding thresholds, same honesty status as every other threshold in this app.
        // 8 skips within 15s is well beyond what even a genuinely bad ad-heavy stretch of
        // real TikTok scrolling would produce; 30s is long enough to actually notice the
        // pause (and the Activity log line explaining it) rather than it blending into the
        // next COOLDOWN_MILLIS-scale gap.
        private const val MAX_CONSECUTIVE_SKIPS = 8
        private const val SKIP_STREAK_WINDOW_MILLIS = 15_000L
        private const val CIRCUIT_BREAKER_PAUSE_MILLIS = 30_000L
        // Escalation on top of the above (see HardStopGuard, docs/auto_scroll_hard_stop/
        // PRD.md) - UNCONFIRMED, same honesty status as every other threshold in this
        // app. 3 trips within 5 minutes means the pause-and-resume cycle above already
        // happened 3 times and did NOT fix itself - a driver reporting "still auto
        // scrolling" after the circuit breaker shipped is exactly this pattern.
        private const val MAX_TRIPS_IN_ESCALATION_WINDOW = 3
        private const val ESCALATION_WINDOW_MILLIS = 5 * 60_000L
        // Per-video watch-duration tracking (docs/video_category_watch_tracking/PRD.md
        // §0.4/§3a-P5) - UNCONFIRMED, reasonable-sounding guesses, same honesty status
        // as every other threshold in this file. 5 minutes bounds the "app was
        // backgrounded mid-video" gap (no accessibility events fire while TikTok isn't
        // foreground, so there's no direct signal for that) without letting one
        // background stretch report as an absurd multi-hour "watch"; 3 seconds is the
        // driver's own "start with that" answer (§5/P3) for what counts as a genuinely
        // watched Post rather than one scrolled past.
        private const val MAX_TRACKED_WATCH_DURATION_MILLIS = 5 * 60_000L
        private const val POST_WATCH_MIN_MILLIS = 3_000L
    }
}
