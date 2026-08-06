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
    val subjectBoostLikes: Int get() = prefs.getInt(KEY_SUBJECT_BOOST_LIKES, 0)
    val audioExtractionsCompleted: Int get() = prefs.getInt(KEY_AUDIO_EXTRACTIONS, 0)

    fun recentLog(): List<String> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun recordSkip(decision: SkipDecision) {
        val counterKey = when (decision.reason) {
            SkipReason.AD -> KEY_ADS_SKIPPED
            SkipReason.BLOCKED_CREATOR -> KEY_CREATORS_SKIPPED
        }
        val newCount = prefs.getInt(counterKey, 0) + 1
        val entry = when (decision.reason) {
            SkipReason.AD -> "Ad skipped (matched \"${decision.detail}\")"
            SkipReason.BLOCKED_CREATOR -> "Blocked creator skipped (${decision.detail})"
        }
        prefs.edit().putInt(counterKey, newCount).apply()
        appendLogEntry(entry)
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
        private const val KEY_SUBJECT_BOOST_LIKES = "subject_boost_likes"
        private const val KEY_AUDIO_EXTRACTIONS = "audio_extractions_completed"
        private const val KEY_LOG = "recent_log"
        private const val MAX_LOG_ENTRIES = 50
    }
}
