package com.tiktokfilter.app.filter

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Persists a view count per video, keyed by [FilterEngine.videoFingerprint], so a video
 * can be auto-skipped once you've genuinely watched it a configured number of times (see
 * SettingsRepository.repeatViewLimit). Only ever incremented for a video that actually
 * played (see TikTokFilterService) - a skipped viewing was never watched, so it's never
 * counted as one.
 *
 * [FilterEngine.videoFingerprint] returns a raw, human-readable string ("creator|caption
 * proxy") for testability; this hashes it (SHA-256, truncated) before using it as a
 * storage key - not for security, just to keep entries a fixed, small size regardless of
 * how long a caption is, and to sidestep any delimiter collision between the fingerprint's
 * own text and this repository's storage format.
 *
 * Bounded to [MAX_TRACKED_VIDEOS] entries, oldest-last-seen evicted first, same reasoning
 * as StatsRepository's capped activity log: without a cap, this would grow forever as a
 * user scrolls through TikTok's effectively unbounded catalog, when in practice only a
 * small fraction of videos are ever seen more than once. The cap size is an UNCONFIRMED,
 * reasonable-sounding guess (like most thresholds in this app) - large enough that a
 * normal scrolling session's worth of repeats should still be caught, not verified against
 * a real, extended-use device.
 */
class RepeatViewRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 0 if [fingerprint] has never been recorded (or was evicted by the LRU cap). */
    fun viewCount(fingerprint: String): Int {
        val entries = readEntries()
        return entries[hash(fingerprint)]?.count ?: 0
    }

    /** Call only for a video that actually played (never for one that got skipped) -
      * increments its count and refreshes its last-seen time, evicting the least-recently-
      * seen entries first if this pushes the tracked set past [MAX_TRACKED_VIDEOS]. */
    fun recordView(fingerprint: String) {
        val key = hash(fingerprint)
        val entries = readEntries().toMutableMap()
        val now = System.currentTimeMillis()
        val existing = entries[key]
        entries[key] = Entry(count = (existing?.count ?: 0) + 1, lastSeenMillis = now)

        if (entries.size > MAX_TRACKED_VIDEOS) {
            val toEvict = entries.entries
                .sortedBy { it.value.lastSeenMillis }
                .take(entries.size - MAX_TRACKED_VIDEOS)
                .map { it.key }
            toEvict.forEach { entries.remove(it) }
        }
        writeEntries(entries)
    }

    /** Forgets every tracked view count - lets a user start over (e.g. after intentionally
      * re-watching something on purpose) without waiting for the LRU cap to age it out. */
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private data class Entry(val count: Int, val lastSeenMillis: Long)

    /** One entry per line: "hashHex:count:lastSeenMillis" - plain delimited text, same
      * convention as SettingsRepository's comma-joined lists, chosen for the same reason
      * (this app deliberately has no JSON/database dependency - see README's Architecture
      * section). A malformed line (shouldn't happen, but a future format change or a
      * corrupted write could produce one) is skipped rather than crashing the whole read. */
    private fun readEntries(): Map<String, Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size != 3) return@mapNotNull null
                val count = parts[1].toIntOrNull() ?: return@mapNotNull null
                val lastSeen = parts[2].toLongOrNull() ?: return@mapNotNull null
                parts[0] to Entry(count, lastSeen)
            }
            .toMap()
    }

    private fun writeEntries(entries: Map<String, Entry>) {
        val raw = entries.entries.joinToString("\n") { (key, entry) -> "$key:${entry.count}:${entry.lastSeenMillis}" }
        prefs.edit().putString(KEY_ENTRIES, raw).apply()
    }

    private fun hash(fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_LENGTH)
    }

    companion object {
        private const val PREFS_NAME = "tiktok_filter_repeat_view"
        private const val KEY_ENTRIES = "entries"
        // UNCONFIRMED, reasonable-sounding guess - not verified against a real, extended
        // scrolling session.
        private const val MAX_TRACKED_VIDEOS = 500
        // 16 hex chars = 64 bits of the 256-bit digest - more than enough collision
        // resistance for a few hundred tracked entries at a time (birthday-bound collision
        // odds at 500 entries over a 64-bit space are astronomically small).
        private const val HASH_HEX_LENGTH = 16
    }
}
