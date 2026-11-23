package dev.wiimauto.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * A Media3 player with no local audio: every command goes to the WiiM, and its state is polled
 * back. Android Auto and steering wheel buttons talk to this through the media session.
 */
@OptIn(UnstableApi::class)
class WiimPlayer(private val connection: WiimConnection) : SimpleBasePlayer(Looper.getMainLooper()) {

    private data class Snapshot(
        val reachable: Boolean = false,
        val status: PlayerStatus? = null,
        val meta: TrackMeta? = null,
        val presets: List<Preset> = emptyList(),
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var snapshot = Snapshot()
    @Volatile private var lastPreset: Int? = null
    private var lastPresetLoad = 0L
    private var released = false

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            io.execute {
                refresh()
                main.post {
                    if (!released) {
                        invalidateState()
                        main.postDelayed(this, POLL_MS)
                    }
                }
            }
        }
    }

    init {
        main.post(poll)
    }

    fun presetsNow(): List<Preset> {
        if (snapshot.presets.isEmpty()) {
            runCatching { connection.api.presets() }.onSuccess { snapshot = snapshot.copy(presets = it) }
        }
        return snapshot.presets
    }

    private fun refresh() {
        try {
            val status = connection.api.status()
            val meta = runCatching { connection.api.meta() }.getOrNull()
            val now = System.currentTimeMillis()
            var presets = snapshot.presets
            if (presets.isEmpty() || now - lastPresetLoad > PRESET_REFRESH_MS) {
                presets = runCatching { connection.api.presets() }.getOrDefault(presets)
                lastPresetLoad = now
            }
            val before = snapshot
            if (!before.reachable) DebugLog.log("wiim", "connected at ${connection.host}")
            if (before.status?.status != status.status || before.status?.vendor != status.vendor) {
                DebugLog.log("wiim", "state ${status.status}, source ${status.vendor}, volume ${status.volume}")
            }
            if (before.meta?.subtitle != meta?.subtitle) DebugLog.log("wiim", "station ${meta?.subtitle.orEmpty()}")
            snapshot = Snapshot(true, status, meta, presets)
            // Follow stations changed from the phone or WiiM app, so blank names keep their place.
            if (status.status == "play") {
                presets.getOrNull(matchPreset(snapshot))?.let { lastPreset = it.number }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WiiM not reachable: ${e.message}")
            if (snapshot.reachable || snapshot.status == null) {
                DebugLog.log("wiim", "not reachable at ${connection.host}: ${e.javaClass.simpleName}: ${e.message}")
            }
            snapshot = snapshot.copy(reachable = false, status = snapshot.status)
        }
    }

    override fun getState(): State {
        val current = snapshot
        val status = current.status
        val (items, index) = buildPlaylist(current)
        val trackBased = status?.isTrackBased == true
        val playing = current.reachable && status?.isPlaying == true
        val position = if (trackBased) status?.positionMs ?: 0 else 0

        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        ).addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, trackBased).build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(
                if (playing) PositionSupplier.getExtrapolating(position, 1f)
                else PositionSupplier.getConstant(position),
            )
            // Repeat-all makes next/previous always available, including on a single live item.
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setMaxSeekToPreviousPositionMs(ALWAYS_PREVIOUS_MS)
            .build()
    }

    private fun buildPlaylist(current: Snapshot): Pair<List<MediaItemData>, Int> {
        val status = current.status
        val live = liveMetadata(current)
        val duration = status?.durationMs?.takeIf { it > 0 }
        val presetIndex = matchPreset(current)

        if (presetIndex < 0) {
            val item = MediaItem.Builder().setMediaId(LIVE_ID).setMediaMetadata(live).build()
            val data = MediaItemData.Builder(LIVE_ID)
                .setMediaItem(item)
                .setMediaMetadata(live)
                .setDurationUs(duration?.let { it * 1000 } ?: C.TIME_UNSET)
                .setIsSeekable(duration != null)
                .build()
            return listOf(data) to 0
        }

        val items = current.presets.mapIndexed { index, preset ->
            // The queue item stays fixed so song changes don't rebuild the car's list and scroll
            // it to the top. Only the separate now-playing metadata carries the live song.
            val station = presetMetadata(preset)
            val id = presetId(preset.number)
            val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(station).build()
            MediaItemData.Builder(id)
                .setMediaItem(item)
                .setMediaMetadata(if (index == presetIndex) live else station)
                .build()
        }
        return items to presetIndex
    }

    private fun matchPreset(current: Snapshot): Int =
        matchPresetIndex(current.presets, current.status, current.meta, lastPreset)

    private fun liveMetadata(current: Snapshot): MediaMetadata {
        if (!current.reachable) {
            return MediaMetadata.Builder()
                .setTitle("WiiM not connected")
                .setArtist(connection.host)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        }
        val meta = current.meta
        val status = current.status
        val title = meta?.title?.ifEmpty { null } ?: status?.fallbackTitle?.ifEmpty { null } ?: "WiiM"
        val artist = meta?.artist?.ifEmpty { null } ?: status?.fallbackArtist.orEmpty()
        val album = meta?.subtitle?.ifEmpty { null } ?: meta?.album.orEmpty()
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(meta?.artUrl?.takeIf { it.startsWith("https://") }?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = command {
        DebugLog.log("control", if (playWhenReady) "play" else "pause")
        if (!playWhenReady) {
            connection.api.pause()
        } else {
            val preset = lastPreset ?: snapshot.presets.firstOrNull()?.number
            if (snapshot.status?.isIdle == true && preset != null) connection.api.playPreset(preset)
            else connection.api.resume()
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = snapshot
        val trackBased = current.status?.isTrackBased == true
        DebugLog.log("control", "seek command $seekCommand, index $mediaItemIndex, tracks $trackBased")
        return when (seekCommand) {
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> command { connection.api.seekTo(positionMs / 1000) }
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (trackBased) command { connection.api.next() } else stepPreset(current, 1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (trackBased) command { connection.api.previous() } else stepPreset(current, -1)
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val preset = current.presets.getOrNull(mediaItemIndex)
                if (preset != null) playPreset(preset.number) else Futures.immediateVoidFuture()
            }
            else -> Futures.immediateVoidFuture()
        }
    }

    private fun stepPreset(current: Snapshot, direction: Int): ListenableFuture<*> {
        val presets = current.presets
        if (presets.isEmpty()) return Futures.immediateVoidFuture()
        val index = matchPreset(current)
        val target = if (index < 0) {
            if (direction > 0) 0 else presets.lastIndex
        } else {
            (index + direction + presets.size) % presets.size
        }
        return playPreset(presets[target].number)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val selected = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
        val number = selected?.mediaId?.removePrefix(PRESET_PREFIX)?.toIntOrNull()
        return if (selected?.mediaId?.startsWith(PRESET_PREFIX) == true && number != null) {
            playPreset(number)
        } else {
            Futures.immediateVoidFuture()
        }
    }

    private fun playPreset(number: Int): ListenableFuture<*> {
        lastPreset = number
        DebugLog.log("control", "preset $number")
        return command(PRESET_SETTLE_MS) { connection.api.playPreset(number) }
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    // Stopping the car session must not silence the WiiM unless the user pressed pause.
    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        return Futures.immediateVoidFuture()
    }

    private fun command(settleMs: Long = COMMAND_SETTLE_MS, action: () -> Unit): ListenableFuture<*> {
        val done = SettableFuture.create<Unit>()
        io.execute {
            try {
                action()
                Thread.sleep(settleMs)
                refresh()
            } catch (e: Exception) {
                Log.w(TAG, "Command failed: ${e.message}")
                DebugLog.log("control", "command failed: ${e.javaClass.simpleName}: ${e.message}")
                snapshot = snapshot.copy(reachable = false)
            }
            main.post { done.set(Unit) }
        }
        return done
    }

    companion object {
        const val PRESET_PREFIX = "preset:"
        private const val LIVE_ID = "live"
        private const val TAG = "WiimPlayer"
        private const val POLL_MS = 2000L
        private const val PRESET_REFRESH_MS = 60_000L
        private const val COMMAND_SETTLE_MS = 150L
        private const val PRESET_SETTLE_MS = 800L
        private const val ALWAYS_PREVIOUS_MS = 24L * 60 * 60 * 1000

        fun presetId(number: Int) = "$PRESET_PREFIX$number"

        fun presetMetadata(preset: Preset): MediaMetadata =
            MediaMetadata.Builder()
                .setTitle(preset.name)
                .setArtist(preset.source)
                .setArtworkUri(preset.artUrl.takeIf { it.startsWith("https://") }?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
    }
}
