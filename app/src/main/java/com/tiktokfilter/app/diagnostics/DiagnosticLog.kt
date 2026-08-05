package com.tiktokfilter.app.diagnostics

import android.content.Context
import com.tiktokfilter.app.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A more verbose, technical companion to StatsRepository's user-facing Activity feed -
 * every screen evaluation (including the raw on-screen text collected, not just the
 * outcome), every automation stage attempt and why it did or didn't advance, every
 * exception caught elsewhere in the app. The Activity log answers "what happened";
 * this answers "why", which is what's actually needed to tune a keyword list or
 * report a bug.
 *
 * A no-op unless SettingsRepository.isDiagnosticLoggingEnabled is on (default off),
 * so callers never need to guard calls with their own enabled-check. Written to a
 * plain file in app-private storage rather than SharedPreferences, since entries can
 * include full raw text dumps that don't belong in a preferences store; size-capped
 * so it can't grow unbounded while logging is left on.
 */
class DiagnosticLog(context: Context, private val settingsRepository: SettingsRepository) {
    private val file = File(context.filesDir, FILE_NAME)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Matches the start of a log entry - used by trimIfOversized to find a safe place to
    // cut, since an entry isn't guaranteed to be exactly one physical line (raw on-screen
    // text can contain an embedded newline, and logError's stack traces are intentionally
    // multi-line).
    private val entryStartRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[""", RegexOption.MULTILINE)

    val filePath: String get() = file.absolutePath
    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    @Synchronized
    fun log(tag: String, message: String) {
        if (!settingsRepository.isDiagnosticLoggingEnabled) return
        val line = "${timeFormat.format(Date())} [$tag] $message\n"
        try {
            file.appendText(line)
        } catch (e: Exception) {
            // A failed diagnostic write should never be the thing that crashes an
            // accessibility service - there's nowhere left to report this failure to.
        }
        trimIfOversized()
    }

    /** Same as [log], but for an exception - includes the message and stack trace,
      * since "it failed" without a trace is rarely enough to actually fix anything. */
    @Synchronized
    fun logError(tag: String, message: String, error: Throwable) {
        if (!settingsRepository.isDiagnosticLoggingEnabled) return
        log(tag, "$message - ${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}")
    }

    @Synchronized
    fun clear() {
        try {
            file.writeText("")
        } catch (e: Exception) {
            // Nothing more useful to do if even clearing fails.
        }
    }

    /** Trimming used to keep the last N *physical lines*, which silently corrupted the
      * log whenever an entry spanned more than one - a raw on-screen caption with an
      * embedded newline, or a logError's (intentionally multi-line) stack trace - since
      * the cut could land in the middle of an entry rather than between two. It also
      * didn't actually bound the file size: entries here vary wildly in length, so "last
      * 2000 lines" could easily still be several times MAX_FILE_SIZE_BYTES. This instead
      * takes roughly the last MAX_FILE_SIZE_BYTES worth of characters, then discards
      * whatever partial entry that cut into the middle of by searching forward for the
      * next real entry boundary - correctly sized, and never a broken first line. */
    private fun trimIfOversized() {
        if (file.length() <= MAX_FILE_SIZE_BYTES) return
        try {
            val text = file.readText()
            val tail = if (text.length > MAX_FILE_SIZE_BYTES) text.takeLast(MAX_FILE_SIZE_BYTES.toInt()) else text
            val firstBoundary = entryStartRegex.find(tail)
            val trimmed = if (firstBoundary != null) tail.substring(firstBoundary.range.first) else tail
            file.writeText(trimmed)
        } catch (e: Exception) {
            // Leaving an oversized file in place is safer than losing it entirely.
        }
    }

    companion object {
        private const val FILE_NAME = "diagnostics.log"
        private const val MAX_FILE_SIZE_BYTES = 512 * 1024L // 512 KB
    }
}
