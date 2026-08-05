package com.tiktokfilter.app.tiktokactions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.tiktokfilter.app.SettingsRepository
import com.tiktokfilter.app.StatsRepository
import com.tiktokfilter.app.diagnostics.DiagnosticLog
import com.tiktokfilter.app.media.AudioExtractor
import com.tiktokfilter.app.media.DownloadedVideoLocator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns the two multi-tap TikTok automations (real Block, and Download-then-extract-audio):
 * advancing each one's [ActionSequence] by searching the accessibility tree for its
 * current stage's keywords and tapping whatever matches, one stage per screen update,
 * until it completes or times out. TikTokFilterService just forwards events here; this
 * is where the actual "how do we interact with TikTok's real UI" logic lives, kept
 * separate so the service itself stays readable.
 *
 * Every outcome - success, timeout, "couldn't find it" - is written to the activity
 * log via [StatsRepository], since these are heuristic automations over TikTok's real
 * UI and need to be auditable if they ever stop working after a TikTok update. [diagnosticLog]
 * additionally records the per-stage detail (which keywords were searched, whether a
 * node was found) that [StatsRepository]'s user-facing log deliberately leaves out.
 */
class TikTokActionCoordinator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: StatsRepository,
    private val diagnosticLog: DiagnosticLog
) {
    private var pendingBlock: ActionSequence? = null
    private var pendingDownload: ActionSequence? = null
    private var downloadTriggeredAtEpochSeconds: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    /** Always adds [handle] to the local blocklist immediately, then - if enabled -
      * kicks off the real-TikTok-block tap sequence too. The local list never depends
      * on the automation succeeding. [isLive] picks which tap sequence to use - a Live
      * room's menu is opened from a different place than a normal video's (see
      * SettingsRepository.liveBlockActionStages). */
    fun startBlockCurrentCreator(handle: String, isLive: Boolean = false) {
        settingsRepository.addBlockedCreator(handle)
        statsRepository.recordEvent("Added $handle to local blocklist")
        diagnosticLog.log("BLOCK", "$handle added to local blocklist (live=$isLive)")
        if (!settingsRepository.isRealBlockAutomationEnabled) {
            diagnosticLog.log("BLOCK", "real block automation disabled in settings, stopping here")
            return
        }
        val stages = if (isLive) settingsRepository.liveBlockActionStages() else settingsRepository.blockActionStages()
        pendingBlock = ActionSequence(stages, System.currentTimeMillis())
        statsRepository.recordEvent("Attempting to block $handle in TikTok directly...")
        diagnosticLog.log("BLOCK", "sequence started for $handle (live=$isLive), stages=$stages")
    }

    /** No-op (with a clear log/activity entry) if [isLive] - a Live broadcast has no
      * saved video file for TikTok's own Save option to act on, so there's nothing here
      * to download; starting the tap sequence anyway would just stall out on a 4-second
      * timeout searching for a "Save video" option that was never going to appear. */
    fun startDownloadCurrentVideo(isLive: Boolean = false) {
        if (isLive) {
            statsRepository.recordEvent("Download isn't available on Live streams")
            diagnosticLog.log("DOWNLOAD", "skipped - current screen is a Live stream")
            return
        }
        pendingDownload = ActionSequence(settingsRepository.downloadActionStages(), System.currentTimeMillis())
        downloadTriggeredAtEpochSeconds = System.currentTimeMillis() / 1000
        statsRepository.recordEvent("Attempting to download the current video...")
        diagnosticLog.log("DOWNLOAD", "sequence started, stages=${settingsRepository.downloadActionStages()}")
    }

    /** Call on every accessibility event for the target app while either automation
      * is in flight - advances whichever sequence(s) are pending. A no-op the rest of
      * the time (both are null), so this is cheap to call unconditionally. */
    fun onScreenUpdated(root: AccessibilityNodeInfo) {
        if (pendingBlock == null && pendingDownload == null) return
        val now = System.currentTimeMillis()

        pendingBlock = advance(
            "BLOCK", pendingBlock, root, now,
            onComplete = { statsRepository.recordEvent("Blocked in TikTok directly") },
            onTimeout = { statsRepository.recordEvent("Couldn't find TikTok's Block option - kept in local list only") }
        )
        pendingDownload = advance(
            "DOWNLOAD", pendingDownload, root, now,
            onComplete = {
                statsRepository.recordEvent("Download tapped - locating the saved file...")
                locateAndExtractAudio(downloadTriggeredAtEpochSeconds, attempt = 0)
            },
            onTimeout = { statsRepository.recordEvent("Couldn't find a Download option for this video - it may not be enabled by the creator") }
        )
    }

    private fun advance(
        tag: String,
        sequence: ActionSequence?,
        root: AccessibilityNodeInfo,
        nowMillis: Long,
        onComplete: () -> Unit,
        onTimeout: () -> Unit
    ): ActionSequence? {
        if (sequence == null) return null
        if (sequence.hasTimedOut(nowMillis, ACTION_TIMEOUT_MILLIS)) {
            diagnosticLog.log(tag, "stage ${sequence.stageIndex} timed out searching for ${sequence.currentStageKeywords}")
            onTimeout()
            return null
        }
        val found = findAndClickNode(root, sequence.currentStageKeywords)
        diagnosticLog.log(tag, "stage ${sequence.stageIndex} searching ${sequence.currentStageKeywords} - found=$found")
        if (!found) return sequence
        val next = sequence.advance()
        if (next.isComplete) {
            diagnosticLog.log(tag, "sequence complete")
            onComplete()
            return null
        }
        return next
    }

    /** Depth-first search for a node whose text or contentDescription contains any of
      * [keywords] (case-insensitive), clicking the first clickable node at or above
      * it in the tree. Mirrors TikTokFilterService.collectText's traversal shape. */
    private fun findAndClickNode(node: AccessibilityNodeInfo?, keywords: List<String>, depth: Int = 0): Boolean {
        if (node == null || depth > MAX_TREE_DEPTH || keywords.isEmpty()) return false

        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val isMatch = keywords.any { keyword ->
            keyword.isNotBlank() && (text.contains(keyword, ignoreCase = true) || description.contains(keyword, ignoreCase = true))
        }
        if (isMatch) {
            val clickable = findClickableSelfOrAncestor(node)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = findAndClickNode(child, keywords, depth + 1)
            @Suppress("DEPRECATION")
            child.recycle()
            if (clicked) return true
        }
        return false
    }

    /** TikTok's clickable target is often an ancestor of the node that actually holds
      * the matched text (an icon + label wrapped in one clickable row) - walks up a
      * bounded number of hops looking for the first node Android considers clickable. */
    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null) {
            if (current.isClickable) return current
            if (hops >= MAX_ANCESTOR_HOPS) return null
            current = current.parent
            hops++
        }
        return null
    }

    /** Polls MediaStore a few times with a short delay - the file TikTok's Save button
      * writes doesn't necessarily exist the instant the tap registers - then hands off
      * to a background thread for the actual extraction, since remuxing shouldn't run
      * on whatever thread accessibility events arrive on. */
    private fun locateAndExtractAudio(afterEpochSeconds: Long, attempt: Int) {
        val uri = DownloadedVideoLocator.findRecentlyAddedVideoUri(context, afterEpochSeconds)
        diagnosticLog.log("EXTRACT", "locate attempt $attempt (after=$afterEpochSeconds) -> $uri")
        if (uri == null) {
            if (attempt >= MAX_LOCATE_ATTEMPTS) {
                statsRepository.recordEvent("Downloaded video wasn't found in the media library - audio extraction skipped")
                diagnosticLog.log("EXTRACT", "gave up after $MAX_LOCATE_ATTEMPTS attempts")
                return
            }
            mainHandler.postDelayed({ locateAndExtractAudio(afterEpochSeconds, attempt + 1) }, LOCATE_RETRY_DELAY_MILLIS)
            return
        }

        backgroundExecutor.execute {
            // getExternalFilesDir can return null if external storage isn't currently
            // available (e.g. removed SD card on some devices) - internal storage is
            // always available as a fallback, just not visible outside the app.
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val outputDir = File(baseDir, "ExtractedAudio").apply { mkdirs() }
            val fileName = "tiktok_audio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
            val outputFile = File(outputDir, fileName)

            var extractionError: Exception? = null
            val success = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    AudioExtractor.extractAudioTrack(pfd.fileDescriptor, outputFile.absolutePath)
                } ?: false
            } catch (e: Exception) {
                extractionError = e
                false
            }

            mainHandler.post {
                if (success) {
                    statsRepository.recordAudioExtracted()
                    statsRepository.recordEvent("Audio extracted to Android/data/.../files/ExtractedAudio/$fileName")
                    diagnosticLog.log("EXTRACT", "success -> ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                } else {
                    outputFile.delete()
                    statsRepository.recordEvent("Audio extraction failed - the video may not have an audio track TikTok saved, or wasn't downloaded")
                    val error = extractionError
                    if (error != null) {
                        diagnosticLog.logError("EXTRACT", "failed for $uri", error)
                    } else {
                        diagnosticLog.log("EXTRACT", "failed for $uri - no audio track found, or AudioExtractor couldn't remux it")
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_TIMEOUT_MILLIS = 4_000L
        private const val MAX_TREE_DEPTH = 60
        private const val MAX_ANCESTOR_HOPS = 6
        private const val MAX_LOCATE_ATTEMPTS = 5
        private const val LOCATE_RETRY_DELAY_MILLIS = 1_500L
    }
}
