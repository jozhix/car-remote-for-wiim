package dev.wiimauto.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Loops digital silence. Android sends media keys to the app that is playing audio, and this app's
 * real audio plays on the WiiM, so steering wheel buttons would otherwise go to another app.
 */
class SilentAudio {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        runCatching {
            val bytes = SAMPLE_RATE * BYTES_PER_FRAME
            val silent = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            silent.write(ByteArray(bytes), 0, bytes)
            silent.setLoopPoints(0, SAMPLE_RATE, -1)
            silent.play()
            track = silent
            DebugLog.log("steering", "silent audio started")
        }.onFailure { DebugLog.log("steering", "silent audio failed: ${it.message}") }
    }

    fun stop() {
        val current = track ?: return
        track = null
        runCatching {
            current.stop()
            current.release()
        }
        DebugLog.log("steering", "silent audio stopped")
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BYTES_PER_FRAME = 2
    }
}
