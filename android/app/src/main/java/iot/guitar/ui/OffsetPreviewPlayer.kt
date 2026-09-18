package iot.guitar.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import iot.guitar.merge.WavPcmReader

/**
 * Plays the picked video in a [VideoView] (its own audio kept at [ORIGINAL_VOLUME]) while
 * feeding the WAV through an [AudioTrack] at `videoPos - offsetMs`. A wrong offset is heard
 * as a double/echoed sound; when it disappears the offset is right. AudioTrack start latency
 * (~20-80 ms) limits preview accuracy; the merge itself is sample-exact.
 */
class OffsetPreviewPlayer(private val videoView: VideoView, private val openWav: () -> WavPcmReader) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var running = false
    @Volatile private var generation = 0   // bumps on every play(); stale feeders must not stop a newer one
    private var onStopped: (() -> Unit)? = null

    val isPlaying: Boolean
        get() = running

    fun setVideo(uri: Uri) {
        stop()
        mediaPlayer = null
        videoView.setOnPreparedListener { mp ->
            mp.setVolume(ORIGINAL_VOLUME, ORIGINAL_VOLUME)
            mediaPlayer = mp
            mp.seekTo(0)
        }
        videoView.setVideoURI(uri)
    }

    /**
     * Starts a [durationMs] preview from [startMs] on the video timeline.
     * @return false if the video is not prepared yet (nothing started, [onStopped] not called).
     */
    fun play(startMs: Long, offsetMs: Long, durationMs: Long = DEFAULT_PREVIEW_MS, onStopped: () -> Unit): Boolean {
        stop()
        val mp = mediaPlayer ?: return false
        this.onStopped = onStopped
        running = true
        val myGeneration = ++generation
        mp.setOnSeekCompleteListener { player ->
            player.setOnSeekCompleteListener(null)
            if (!running || myGeneration != generation) return@setOnSeekCompleteListener
            player.start()
            startFeeder(startMs, offsetMs, durationMs, myGeneration)
        }
        mp.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)  // frame-accurate, not previous keyframe
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        try { mediaPlayer?.pause() } catch (_: IllegalStateException) { }
        onStopped?.invoke()
        onStopped = null
    }

    fun release() {
        stop()
        videoView.stopPlayback()
        mediaPlayer = null
    }

    private fun startFeeder(startMs: Long, offsetMs: Long, durationMs: Long, myGeneration: Int) {
        Thread({ feedAudio(startMs, offsetMs, durationMs, myGeneration) }, "umic-preview").start()
    }

    private fun feedAudio(startMs: Long, offsetMs: Long, durationMs: Long, myGeneration: Int) {
        var track: AudioTrack? = null
        var reader: WavPcmReader? = null
        try {
            val r = openWav()
            reader = r
            val channelMask = if (r.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(r.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(r.sampleRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(maxOf(minBuf * 2, CHUNK_BYTES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t

            // WAV time = video time - offset (see OffsetPcmSource convention).
            val wavStartMs = startMs - offsetMs
            var zeroBytes = if (wavStartMs < 0) -wavStartMs * r.sampleRate / 1000 * r.bytesPerFrame else 0L
            r.seekToSample(maxOf(0L, wavStartMs) * r.sampleRate / 1000)
            val limitBytes = durationMs * r.sampleRate / 1000 * r.bytesPerFrame
            val zeros = ByteArray(CHUNK_BYTES)
            val buf = ByteArray(CHUNK_BYTES)
            var written = 0L
            t.play()
            while (running && myGeneration == generation && written < limitBytes) {
                val n = if (zeroBytes > 0) {
                    val z = minOf(zeroBytes, CHUNK_BYTES.toLong()).toInt()
                    zeroBytes -= z
                    t.write(zeros, 0, z)
                } else {
                    val got = r.read(buf)
                    if (got <= 0) break
                    t.write(buf, 0, got)
                }
                if (n <= 0) break
                written += n
            }
        } catch (_: Exception) {
            // Preview is best-effort; fall through to cleanup.
        } finally {
            // pause()+flush() drops the buffered tail immediately; stop() would drain ~170 ms.
            try { track?.pause(); track?.flush() } catch (_: IllegalStateException) { }
            track?.release()
            try { reader?.close() } catch (_: Exception) { }
            mainHandler.post { if (myGeneration == generation) stop() }
        }
    }

    companion object {
        const val DEFAULT_PREVIEW_MS = 10_000L
        private const val CHUNK_BYTES = 8192
        private const val ORIGINAL_VOLUME = 0.7f
    }
}
