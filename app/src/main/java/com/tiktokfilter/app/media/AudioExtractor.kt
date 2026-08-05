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

    /** [success] used to be judged purely on "did the muxer throw" - which meant a
      * technically-valid but empty output file (zero samples ever written, e.g. because
      * the source's audio track was present but unreadable) still reported as a success.
      * [sampleCount] and [durationMillis] exist specifically so both this code and a
      * human reading the Activity/Diagnostic log can actually judge whether what came out
      * is a complete extraction rather than a fragment - a duration wildly shorter than
      * the source video is the clearest available signal something went wrong, since
      * there's no independent "expected length" to compare against here. */
    data class ExtractionResult(
        val success: Boolean,
        val sampleCount: Int,
        val durationMillis: Long,
        // Distinguishes "there was no audio track to extract at all" from "a track was
        // found but reading it produced zero samples" - both otherwise look identical
        // (success=false, sampleCount=0), but they're different failures worth telling
        // apart in a log rather than reporting one generic "couldn't do it" for both.
        val hadAudioTrack: Boolean
    )

    /** Copies the audio track out of [sourceFd] into [destinationPath]. Every "couldn't
      * do it" case - no audio track, zero samples actually read, an audio codec
      * MediaMuxer can't remux, a read/write failure - reports as [ExtractionResult.success]
      * false rather than throwing, since the caller only needs to know whether to report
      * success or fall back to reporting failure. */
    fun extractAudioTrack(sourceFd: FileDescriptor, destinationPath: String): ExtractionResult {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        // Declared outside the try block so the catch below can still report an accurate
        // hadAudioTrack even if the exception happened after a track was already found
        // and selected (e.g. mid-copy), not just during the initial track search.
        var hadAudioTrack = false
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
            val format = audioFormat ?: return ExtractionResult(false, 0, 0, hadAudioTrack = false)
            hadAudioTrack = true
            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(destinationPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0
            var lastPresentationTimeUs = 0L
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                sampleCount++
                lastPresentationTimeUs = extractor.sampleTime
                extractor.advance()
            }
            // A muxer that never threw but also never received a single sample is not a
            // successful extraction - it's an empty, technically-valid audio file.
            ExtractionResult(sampleCount > 0, sampleCount, lastPresentationTimeUs / 1000, hadAudioTrack = true)
        } catch (e: Exception) {
            ExtractionResult(false, 0, 0, hadAudioTrack = hadAudioTrack)
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
