package com.tiktokfilter.app

import android.content.Context
import android.content.SharedPreferences
import com.tiktokfilter.app.filter.FilterEngine

/**
 * All user-configurable filter settings - comma-separated strings rather than
 * SharedPreferences' StringSet, deliberately, since StringSet doesn't preserve
 * insertion order and these lists are meant to be readable/editable as an
 * ordered list in the UI.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAdSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_SKIP_ENABLED, value).apply()

    var isBlockedCreatorSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED_CREATOR_SKIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKED_CREATOR_SKIP_ENABLED, value).apply()

    /** Which app package(s) the service is allowed to read/act on - a list (not one value)
      * since TikTok ships under different package names by region/variant (see README). */
    var targetPackages: List<String>
        get() = parseList(prefs.getString(KEY_TARGET_PACKAGES, null) ?: DEFAULT_TARGET_PACKAGES.joinToString(","))
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGES, joinList(value)).apply()

    var adKeywords: List<String>
        get() = parseList(prefs.getString(KEY_AD_KEYWORDS, null) ?: DEFAULT_AD_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_AD_KEYWORDS, joinList(value)).apply()

    /** Always normalized (lowercase, no leading '@') - callers never need to normalize
      * on read, only addBlockedCreator normalizes, on the way in. */
    var blockedCreators: List<String>
        get() = parseList(prefs.getString(KEY_BLOCKED_CREATORS, null) ?: "")
        set(value) = prefs.edit().putString(KEY_BLOCKED_CREATORS, joinList(value)).apply()

    fun addAdKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        if (adKeywords.any { it.equals(trimmed, ignoreCase = true) }) return
        adKeywords = adKeywords + trimmed
    }

    fun removeAdKeyword(keyword: String) {
        adKeywords = adKeywords.filterNot { it.equals(keyword, ignoreCase = true) }
    }

    fun addBlockedCreator(handle: String) {
        val normalized = FilterEngine.normalizeHandle(handle)
        if (normalized.isEmpty() || normalized in blockedCreators) return
        blockedCreators = blockedCreators + normalized
    }

    fun removeBlockedCreator(handle: String) {
        val normalized = FilterEngine.normalizeHandle(handle)
        blockedCreators = blockedCreators.filterNot { it == normalized }
    }

    private fun parseList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinList(items: List<String>): String = items.joinToString(",")

    companion object {
        private const val PREFS_NAME = "tiktok_filter_settings"
        private const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
        private const val KEY_BLOCKED_CREATOR_SKIP_ENABLED = "blocked_creator_skip_enabled"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_AD_KEYWORDS = "ad_keywords"
        private const val KEY_BLOCKED_CREATORS = "blocked_creators"

        // com.zhiliaoapp.musically is global/US TikTok; com.ss.android.ugc.trill has been
        // used for TikTok in some regions/older builds. Both are included by default so
        // the app works out of the box for most installs; add/remove in Setup if needed.
        val DEFAULT_TARGET_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val DEFAULT_AD_KEYWORDS = listOf("Sponsored")
    }
}
