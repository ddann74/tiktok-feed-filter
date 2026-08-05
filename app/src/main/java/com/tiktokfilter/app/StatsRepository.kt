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

    fun recentLog(): List<String> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun recordSkip(decision: SkipDecision) {
        val counterKey = if (decision.reason == SkipReason.AD) KEY_ADS_SKIPPED else KEY_CREATORS_SKIPPED
        val newCount = prefs.getInt(counterKey, 0) + 1
        val entry = timeFormat.format(System.currentTimeMillis()) + " - " + when (decision.reason) {
            SkipReason.AD -> "Ad skipped (matched \"${decision.detail}\")"
            SkipReason.BLOCKED_CREATOR -> "Blocked creator skipped (${decision.detail})"
        }
        val updatedLog = (listOf(entry) + recentLog()).take(MAX_LOG_ENTRIES)
        prefs.edit()
            .putInt(counterKey, newCount)
            .putString(KEY_LOG, updatedLog.joinToString("\n"))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "tiktok_filter_stats"
        private const val KEY_ADS_SKIPPED = "ads_skipped"
        private const val KEY_CREATORS_SKIPPED = "creators_skipped"
        private const val KEY_LOG = "recent_log"
        private const val MAX_LOG_ENTRIES = 50
    }
}
