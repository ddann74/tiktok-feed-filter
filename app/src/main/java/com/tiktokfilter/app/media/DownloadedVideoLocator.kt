package com.tiktokfilter.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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

    /** Most recently added video whose DATE_ADDED is at or after [afterEpochSeconds],
      * or null if nothing new has shown up yet (the caller is expected to retry a few
      * times with a short delay, since the write isn't necessarily instant). */
    fun findRecentlyAddedVideoUri(context: Context, afterEpochSeconds: Long): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
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
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }
}
