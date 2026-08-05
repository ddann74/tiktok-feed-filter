package com.tiktokfilter.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Pulls the audio track out of a video file already sitting on the device (via its
 * file descriptor) and writes it to a new .m4a file - pure container remuxing using
 * only Android's built-in MediaExtractor/MediaMuxer, no re-encoding, no external
 * library, and no network access of any kind. This never sources a video itself; it
 * only ever operates on a file that TikTok's own Save action has already written.
 */
object AudioExtractor {

    /** Returns true if an audio track was found and copied to [destinationPath]. False
      * (not an exception) covers every "couldn't do it" case - no audio track, an
      * audio codec MediaMuxer can't remux, a read/write failure - since the caller
      * only needs to know whether to report success or fall back to reporting failure. */
    fun extractAudioTrack(sourceFd: FileDescriptor, destinationPath: String): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(sourceFd)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat ?: return false
            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(destinationPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                // Already failed above if this was ever going to succeed - swallow so
                // cleanup always completes rather than masking the real error.
            }
            muxer?.release()
            extractor.release()
        }
    }

    private const val BUFFER_SIZE_BYTES = 1 shl 20 // 1 MB - generous for a single audio chunk
}
