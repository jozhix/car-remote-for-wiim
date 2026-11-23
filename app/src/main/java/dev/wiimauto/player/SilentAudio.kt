package dev.wiimauto.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.random.Random

/**
 * Loops inaudible noise. Android sends media keys to the app that is playing audio, and this app's
 * real audio plays on the WiiM, so steering wheel buttons would otherwise go to another app.
 *
 * Audio focus is taken only when the user plays something here. If they then start Spotify, it
 * becomes the newest player and gets the steering wheel until they choose something here again.
 * Focus changes from Android Auto or Waze prompts do not stop the silence.
 */
class SilentAudio(context: Context) {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change ->
            DebugLog.log("steering", "audio focus change $change")
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                // Android Auto takes focus by itself, so losing it doesn't mean another player started.
                // The silence keeps running. A real player that starts later gets the keys from Android.
                hasFocus = false
                DebugLog.log("steering", "audio focus lost, silence continues")
            }
        }
        .build()

    private var track: AudioTrack? = null
    private var hasFocus = false

    fun start() {
        if (track != null) return
        runCatching {
            val bytes = SAMPLE_RATE * BYTES_PER_FRAME
            val silent = AudioTrack.Builder()
                .setAudioAttributes(attributes)
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
            silent.write(inaudibleNoise(bytes), 0, bytes)
            silent.setLoopPoints(0, SAMPLE_RATE, -1)
            silent.play()
            track = silent
            DebugLog.log("steering", "silent audio started")
        }.onFailure { DebugLog.log("steering", "silent audio failed: ${it.message}") }
    }

    /** The user chose this app, so take the media keys back from any other player. */
    fun claimKeys() {
        if (hasFocus) {
            start()
            return
        }
        // Android gives the keys to the player that started most recently, so start again.
        stopTrack()
        start()
        val result = audio.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLog.log("steering", "audio focus ${if (hasFocus) "granted" else "refused ($result)"}")
    }

    fun stop() {
        if (hasFocus) audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        stopTrack()
    }

    /** About -84 dBFS. Pure zeros did not make the car switch to Android Auto audio. */
    private fun inaudibleNoise(bytes: Int): ByteArray {
        val random = Random(1)
        val data = ByteArray(bytes)
        for (index in 0 until bytes - 1 step BYTES_PER_FRAME) {
            val sample = random.nextInt(-NOISE_PEAK, NOISE_PEAK + 1)
            data[index] = (sample and 0xFF).toByte()
            data[index + 1] = (sample shr 8 and 0xFF).toByte()
        }
        return data
    }

    private fun stopTrack() {
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
        const val NOISE_PEAK = 2
    }
}
