package iot.guitar.merge

import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.io.IOException

/**
 * Copies the video track of [videoUri] untouched and muxes the WAV (through
 * [OffsetPcmSource] + [AacPcmEncoder]) as a new AAC track, interleaving samples by PTS so
 * the MP4 is well-formed and seekable. Streams everything: memory stays flat.
 */
class VideoAudioMerger(private val context: Context) {

    /** Merge failure; [likelyHdr] lets the UI show the HDR/Dolby Vision guidance. */
    class MergeException(message: String, val likelyHdr: Boolean, cause: Throwable? = null) :
        IOException(message, cause)

    fun merge(
        videoUri: Uri,
        wav: WavPcmReader,
        offsetMs: Long,
        output: FileDescriptor,
        onProgress: (percent: Int) -> Unit,
    ) {
        val video = VideoTrackReader(context, videoUri)
        var muxer: MediaMuxer? = null
        var encoder: AacPcmEncoder? = null
        var videoSamplesWritten = 0L
        try {
            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = try {
                muxer.addTrack(video.format)
            } catch (e: Exception) {
                throw MergeException("video track rejected: ${e.message}", video.looksHdr, e)
            }
            muxer.setOrientationHint(video.rotationDegrees)

            val source = OffsetPcmSource(wav, offsetMs, video.durationUs,
                leadSkipSamples = AacPcmEncoder.ENCODER_DELAY_SAMPLES)
            val enc = AacPcmEncoder(wav.sampleRate, wav.channels)
            encoder = enc
            val audio = AudioFeeder(source, enc)
            audio.primeUntilFormatKnown()
            val audioTrack = muxer.addTrack(enc.outputFormat!!)
            muxer.start()

            var nextVideo = video.readSample()
            var nextAudio = audio.next()
            var lastPercent = -1
            var lastAudioPts = -1L
            while (nextVideo != null || nextAudio != null) {
                val writeVideo = nextAudio == null ||
                    (nextVideo != null && nextVideo.presentationTimeUs <= nextAudio.info.presentationTimeUs)
                if (writeVideo) {
                    muxer.writeSampleData(videoTrack, video.sampleBuffer, nextVideo!!)
                    videoSamplesWritten++
                    val percent = if (video.durationUs > 0) (nextVideo.presentationTimeUs * 100 / video.durationUs).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent.coerceIn(0, 100))
                    }
                    nextVideo = video.readSample()
                } else {
                    val sample = nextAudio!!
                    // Encoder PTS derives from sample counts, so it is monotonic; clamp defensively.
                    if (sample.info.presentationTimeUs < lastAudioPts) sample.info.presentationTimeUs = lastAudioPts
                    lastAudioPts = sample.info.presentationTimeUs
                    muxer.writeSampleData(audioTrack, sample.data, sample.info)
                    nextAudio = audio.next()
                }
            }
            muxer.stop()
            onProgress(100)
        } catch (e: MergeException) {
            throw e
        } catch (e: Exception) {
            // Blame HDR only if the muxer never accepted a video sample (disk-full etc. are not HDR).
            throw MergeException(e.message ?: e.javaClass.simpleName, video.looksHdr && videoSamplesWritten == 0L, e)
        } finally {
            encoder?.close()
            try { muxer?.release() } catch (_: Exception) { }
            video.close()
        }
    }

    /** Pulls PCM chunks into the encoder on demand and hands back encoded samples in order. */
    private class AudioFeeder(private val source: OffsetPcmSource, private val encoder: AacPcmEncoder) {
        private var sourceDone = false

        fun primeUntilFormatKnown() {
            while (encoder.outputFormat == null) {
                val fed = feedOne()
                // Drain into the encoder's queue (samples are kept for next()); wait a bit after EOS.
                encoder.drain(if (fed) 0 else PULL_WAIT_US)
                if (!fed && encoder.endOfStream && encoder.outputFormat == null) {
                    throw MergeException("audio encoder produced no format", false)
                }
            }
        }

        fun next(): AacPcmEncoder.EncodedSample? {
            while (true) {
                encoder.pull(0)?.let { return it }
                if (encoder.endOfStream) return null
                if (!feedOne()) {
                    encoder.pull(PULL_WAIT_US)?.let { return it }
                }
            }
        }

        /** Feeds one chunk; signals end-of-stream once the source is exhausted. Returns false after EOS. */
        private fun feedOne(): Boolean {
            if (sourceDone) return false
            val chunk = source.next()
            if (chunk == null) {
                sourceDone = true
                encoder.signalEnd()
                return false
            }
            encoder.feed(chunk.data, chunk.length, chunk.ptsUs)
            return true
        }

        companion object {
            private const val PULL_WAIT_US = 10_000L
        }
    }
}
