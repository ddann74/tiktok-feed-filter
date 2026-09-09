package com.tiktokfilter.app

import android.content.Context
import android.content.SharedPreferences
import com.tiktokfilter.app.filter.SkipDecision
import com.tiktokfilter.app.filter.SkipReason
import java.text.SimpleDateFormat
import java.util.Locale

/** Skip counters plus a capped, newest-first activity log - the log exists mainly so a
  * heuristic, best-effort filter like this one is auditable: if something seems off, you
  * can see exactly what got skipped and why, rather than just trusting a black box. */
class StatsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val adsSkipped: Int get() = prefs.getInt(KEY_ADS_SKIPPED, 0)
    val creatorsSkipped: Int get() = prefs.getInt(KEY_CREATORS_SKIPPED, 0)
    val repeatViewsSkipped: Int get() = prefs.getInt(KEY_REPEAT_VIEWS_SKIPPED, 0)
    val subjectBoostLikes: Int get() = prefs.getInt(KEY_SUBJECT_BOOST_LIKES, 0)
    val audioExtractionsCompleted: Int get() = prefs.getInt(KEY_AUDIO_EXTRACTIONS, 0)

    fun recentLog(): List<String> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    /** [watchDurationMillis]: how long this video was on screen before the skip fired
      * (docs/video_category_watch_tracking/PRD.md §2.1) - enriches the existing skip
      * line rather than adding a new Activity log entry, so the new per-video category
      * tracking feature adds zero volume for Ad/BlockedCreator/RepeatView (all three
      * already had their own line before that PRD existed). Null (the default) keeps
      * every pre-existing caller/test compiling unchanged and produces the exact same
      * entry text as before. */
    fun recordSkip(decision: SkipDecision, watchDurationMillis: Long? = null) {
        val counterKey = when (decision.reason) {
            SkipReason.AD -> KEY_ADS_SKIPPED
            SkipReason.BLOCKED_CREATOR -> KEY_CREATORS_SKIPPED
            SkipReason.REPEAT_VIEW -> KEY_REPEAT_VIEWS_SKIPPED
        }
        val newCount = prefs.getInt(counterKey, 0) + 1
        val baseEntry = when (decision.reason) {
            SkipReason.AD -> "Ad skipped (matched \"${decision.detail}\")"
            SkipReason.BLOCKED_CREATOR -> "Blocked creator skipped (${decision.detail})"
            SkipReason.REPEAT_VIEW -> "Repeat video skipped (${decision.detail})"
        }
        val entry = if (watchDurationMillis != null) {
            "$baseEntry - on screen ${watchDurationMillis / 1000}s"
        } else {
            baseEntry
        }
        prefs.edit().putInt(counterKey, newCount).apply()
        appendLogEntry(entry)
    }

    /** New per-video category tracking entry (docs/video_category_watch_tracking/
      * PRD.md §2.1/§3) - Unidentified videos are expected to be rare, so every
      * occurrence gets logged, unlike Post below. */
    fun recordUnidentifiedWatch(durationMillis: Long) {
        appendLogEntry("Unidentified video watched ${durationMillis / 1000}s")
    }

    /** New per-video category tracking entry (docs/video_category_watch_tracking/
      * PRD.md §2.1/§3) - the caller only invokes this once a Post's capped duration has
      * already crossed the "was this actually watched" threshold (§2.1/P3), so every
      * call here is expected to be a real entry, not filtered further here. */
    fun recordPostWatch(durationMillis: Long) {
        appendLogEntry("Post watched ${durationMillis / 1000}s")
    }

    /** Subject Boost's auto-like counter - separate from [recordSkip] since this isn't a
      * skip decision at all, just a positive engagement signal on a video that was left
      * alone and played normally. */
    fun recordSubjectBoostLike() {
        prefs.edit().putInt(KEY_SUBJECT_BOOST_LIKES, subjectBoostLikes + 1).apply()
        appendLogEntry("Auto-liked (Subject Boost match)")
    }

    /** Free-form log entries for everything that isn't a filter skip - block/download
      * automation progress and outcomes, mainly. Doesn't touch any counter on its own. */
    fun recordEvent(message: String) {
        appendLogEntry(message)
    }

    fun recordAudioExtracted() {
        prefs.edit().putInt(KEY_AUDIO_EXTRACTIONS, audioExtractionsCompleted + 1).apply()
    }

    private fun appendLogEntry(message: String) {
        val entry = timeFormat.format(System.currentTimeMillis()) + " - " + message
        val updatedLog = (listOf(entry) + recentLog()).take(MAX_LOG_ENTRIES)
        prefs.edit().putString(KEY_LOG, updatedLog.joinToString("\n")).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "tiktok_filter_stats"
        private const val KEY_ADS_SKIPPED = "ads_skipped"
        private const val KEY_CREATORS_SKIPPED = "creators_skipped"
        private const val KEY_REPEAT_VIEWS_SKIPPED = "repeat_views_skipped"
        private const val KEY_SUBJECT_BOOST_LIKES = "subject_boost_likes"
        private const val KEY_AUDIO_EXTRACTIONS = "audio_extractions_completed"
        private const val KEY_LOG = "recent_log"
        private const val MAX_LOG_ENTRIES = 50
    }
}
