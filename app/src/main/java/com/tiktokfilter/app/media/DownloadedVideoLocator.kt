package com.tiktokfilter.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * There's no direct way to ask "what file did TikTok's Save button just write" - the
 * automation only knows it tapped the button, not the result. This looks for whatever
 * video showed up in the shared MediaStore collection most recently after that tap,
 * which is the closest available substitute. Requires read access to the device's
 * media collection (READ_EXTERNAL_STORAGE pre-33 / READ_MEDIA_VIDEO on 33+), since
 * this reads a file TikTok owns, not one this app created itself.
 */
object DownloadedVideoLocator {

    /** [ownerPackage] is MediaStore's own record of which app wrote this row, when the
      * platform/device actually populates it (API 29+, and not guaranteed reliable even
      * then) - null otherwise. Deliberately informational only, not used to reject a
      * match (see [findRecentlyAddedVideo]'s doc for why), so the caller can log a
      * mismatch as a warning worth checking rather than silently trusting or silently
      * discarding it. */
    data class LocatedVideo(val uri: Uri, val ownerPackage: String?)

    /** Most recently added video whose DATE_ADDED is at or after [afterEpochSeconds],
      * or null if nothing new has shown up yet (the caller is expected to retry a few
      * times with a short delay, since the write isn't necessarily instant).
      *
      * Deliberately does NOT filter by which app wrote the row, even though MediaStore
      * can record that (OWNER_PACKAGE_NAME, API 29+): that column isn't populated
      * consistently across every OEM/write-path, and turning it into a hard filter risks
      * a strictly worse failure mode than today's - going from "usually right, rarely
      * grabs an unrelated video" to "never finds anything at all" on any device where
      * the assumption doesn't hold, which is worse for the stated priority (reliably
      * getting *an* extraction) and impossible to verify without a real device to test
      * against. Instead [LocatedVideo.ownerPackage] surfaces what MediaStore reported so
      * the caller can log a mismatch as a diagnostic warning, without ever making that
      * the difference between success and failure. */
    fun findRecentlyAddedVideo(context: Context, afterEpochSeconds: Long): LocatedVideo? {
        val includeOwnerColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (includeOwnerColumn) {
            arrayOf(MediaStore.Video.Media._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        } else {
            arrayOf(MediaStore.Video.Media._ID)
        }
        val selection = "${MediaStore.Video.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(afterEpochSeconds.toString())
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val ownerPackage = if (includeOwnerColumn) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    if (columnIndex >= 0) cursor.getString(columnIndex) else null
                } else {
                    null
                }
                return LocatedVideo(uri, ownerPackage)
            }
        }
        return null
    }
}
