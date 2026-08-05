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

    /** Whether adding someone to Blocked Creators should also attempt to tap TikTok's
      * own real Block button, in addition to (never instead of) our own list. Off by
      * default would mean local-list-only; this app defaults it on since that's the
      * whole point of "quicker/more permanent than the app itself" - but it's a real
      * accessibility automation sequence over heuristic keyword matches, so a way to
      * turn it off is kept in case it ever mis-taps something on a given TikTok version. */
    var isRealBlockAutomationEnabled: Boolean
        get() = prefs.getBoolean(KEY_REAL_BLOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REAL_BLOCK_ENABLED, value).apply()

    /** The floating Block/Download buttons drawn over TikTok - on by default since
      * that's what was actually asked for, but it's a visible overlay on top of
      * another app, so a way to turn it off without disabling filtering entirely
      * is worth having. */
    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

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

    // The four lists below drive the multi-tap automations (real Block, and Download)
    // in TikTokActionCoordinator. Each is a set of candidate button labels, not one
    // exact string, because TikTok's real wording can't be verified without a live
    // device - these are best-effort defaults, editable in Setup without a rebuild if
    // a tap sequence stops finding its target.

    var moreOptionsKeywords: List<String>
        get() = parseList(prefs.getString(KEY_MORE_OPTIONS_KEYWORDS, null) ?: DEFAULT_MORE_OPTIONS_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_MORE_OPTIONS_KEYWORDS, joinList(value)).apply()

    var blockOptionKeywords: List<String>
        get() = parseList(prefs.getString(KEY_BLOCK_OPTION_KEYWORDS, null) ?: DEFAULT_BLOCK_OPTION_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_BLOCK_OPTION_KEYWORDS, joinList(value)).apply()

    var blockConfirmKeywords: List<String>
        get() = parseList(prefs.getString(KEY_BLOCK_CONFIRM_KEYWORDS, null) ?: DEFAULT_BLOCK_CONFIRM_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_BLOCK_CONFIRM_KEYWORDS, joinList(value)).apply()

    var downloadOptionKeywords: List<String>
        get() = parseList(prefs.getString(KEY_DOWNLOAD_OPTION_KEYWORDS, null) ?: DEFAULT_DOWNLOAD_OPTION_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_OPTION_KEYWORDS, joinList(value)).apply()

    /** The full tap sequence for "really block this creator in TikTok": open the video's
      * own options menu, tap Block, tap the confirm dialog's Block button. */
    fun blockActionStages(): List<List<String>> =
        listOf(moreOptionsKeywords, blockOptionKeywords, blockConfirmKeywords)

    /** The full tap sequence for "download this video via TikTok's own Save option":
      * open the options menu, tap Save/Download. No confirm step - TikTok's own save
      * action doesn't have one. */
    fun downloadActionStages(): List<List<String>> =
        listOf(moreOptionsKeywords, downloadOptionKeywords)

    fun addAdKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        if (adKeywords.any { it.equals(trimmed, ignoreCase = true) }) return
        adKeywords = adKeywords + trimmed
    }

    fun removeAdKeyword(keyword: String) {
        adKeywords = adKeywords.filterNot { it.equals(keyword, ignoreCase = true) }
    }

    /** Shared add/remove for the four action-sequence keyword lists, which are all
      * plain "unique, case-insensitive, order-preserving" lists just like adKeywords -
      * takes a property reference so the four lists don't need eight near-identical
      * methods repeating the same three lines each. */
    fun addKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        if (list.get().any { it.equals(trimmed, ignoreCase = true) }) return
        list.set(list.get() + trimmed)
    }

    fun removeKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        list.set(list.get().filterNot { it.equals(keyword, ignoreCase = true) })
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
        private const val KEY_REAL_BLOCK_ENABLED = "real_block_automation_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_AD_KEYWORDS = "ad_keywords"
        private const val KEY_BLOCKED_CREATORS = "blocked_creators"
        private const val KEY_MORE_OPTIONS_KEYWORDS = "more_options_keywords"
        private const val KEY_BLOCK_OPTION_KEYWORDS = "block_option_keywords"
        private const val KEY_BLOCK_CONFIRM_KEYWORDS = "block_confirm_keywords"
        private const val KEY_DOWNLOAD_OPTION_KEYWORDS = "download_option_keywords"

        // com.zhiliaoapp.musically is global/US TikTok; com.ss.android.ugc.trill has been
        // used for TikTok in some regions/older builds. Both are included by default so
        // the app works out of the box for most installs; add/remove in Setup if needed.
        val DEFAULT_TARGET_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val DEFAULT_AD_KEYWORDS = listOf("Sponsored")

        // Best-effort candidate labels for TikTok's own UI - not verified against a live
        // device, editable in Setup. See README for what to do if a tap sequence stalls.
        val DEFAULT_MORE_OPTIONS_KEYWORDS = listOf("More options", "More", "Share")
        val DEFAULT_BLOCK_OPTION_KEYWORDS = listOf("Block")
        val DEFAULT_BLOCK_CONFIRM_KEYWORDS = listOf("Block")
        val DEFAULT_DOWNLOAD_OPTION_KEYWORDS = listOf("Save video", "Save", "Download")
    }
}
