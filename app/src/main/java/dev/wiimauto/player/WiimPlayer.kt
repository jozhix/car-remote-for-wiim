package dev.wiimauto.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.json.JSONException
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
        val playerBusy: Boolean = false,
    )

    private val io = Executors.newSingleThreadExecutor()
    private val pollIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var snapshot = Snapshot()
    private var lastPreset: Int?
        get() = connection.lastPreset
        set(value) {
            connection.lastPreset = value
        }

    // The WiiM keeps reporting the old station for a moment after a preset change.
    @Volatile private var pendingPreset: Int? = null
    @Volatile private var pendingUntil = 0L
    private var lastPublished: Pair<Int, MediaMetadata>? = null

    var onNowPlaying: ((MediaMetadata) -> Unit)? = null

    /** Called on the main thread when the browse lists on the car screen need reloading. */
    var onBrowseChanged: (() -> Unit)? = null

    /** Called on the main thread when the station, stream format, source or volume changes. */
    var onInfoChanged: (() -> Unit)? = null
    private var lastInfoKey: List<Any?>? = null

    /** Called when the user picks something to play here, so this app should own the media keys. */
    var onUserPlayback: (() -> Unit)? = null
    private var lastPresetLoad = 0L
    private var released = false
    @Volatile private var searchUntil = 0L
    @Volatile private var volumeNote = ""
    @Volatile private var volumeNoteUntil = 0L
    private var connectedAction: ((Boolean) -> Unit)? = null

    // Android Auto sends play right after a tapped row, which must not start music for a setting.
    @Volatile private var ignorePlayUntil = 0L

    private val countdown = object : Runnable {
        override fun run() {
            if (released) return
            publishNowPlaying(force = false)
            if (!snapshot.reachable && SystemClock.elapsedRealtime() < searchUntil + 1000) {
                main.postDelayed(this, 1000)
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            // Polling has its own thread, so a slow WiiM reply never delays a button press.
            pollIo.execute {
                refresh()
                main.post {
                    if (!released) {
                        invalidateState()
                        publishNowPlaying(force = false)
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

    /** Shows a countdown and searches quickly while the WiiM joins the hotspot. */
    fun startConnecting() {
        if (snapshot.reachable) return
        searchUntil = SystemClock.elapsedRealtime() + SEARCH_WINDOW_MS
        connection.searchFast(SEARCH_WINDOW_MS)
        DebugLog.log("wiim", "searching for up to ${SEARCH_WINDOW_MS / 1000} s")
        main.removeCallbacks(countdown)
        main.post(countdown)
    }

    fun infoRows() = wiimInfoRows(connection)
    fun eqNow() = runCatching { connection.api.eq() }.getOrNull()
    fun eqPresetsNow() = runCatching { connection.api.eqPresets() }.getOrDefault(emptyList())
    fun volumeNow() = snapshot.status?.volume

    fun fixedVolumeNow() = runCatching { connection.api.stats().fixedVolume }.getOrNull()

    /** The car's + and - buttons. The new level shows briefly under the song title. */
    fun changeVolume(delta: Int) {
        command {
            if (connection.api.stats().fixedVolume) {
                DebugLog.log("control", "volume ignored, Fixed Volume is on")
                showVolumeNote("Fixed Volume is on")
                return@command
            }
            val current = connection.api.status().volume
            val target = (current + delta).coerceIn(0, 100)
            DebugLog.log("control", "car volume $current to $target")
            connection.api.setVolume(target)
            showVolumeNote("WiiM volume $target%")
        }
    }

    private fun showVolumeNote(text: String) {
        volumeNote = text
        volumeNoteUntil = SystemClock.elapsedRealtime() + VOLUME_NOTE_MS
        main.postDelayed({ if (!released) publishNowPlaying(force = false) }, VOLUME_NOTE_MS + 100)
    }

    /** Runs once the WiiM answers, now if it already does. Tells the action if a track source is playing. */
    fun whenConnected(action: (Boolean) -> Unit) {
        if (snapshot.reachable) action(isTrackSource(snapshot)) else connectedAction = action
    }

    fun cancelWhenConnected() {
        connectedAction = null
    }

    private fun runRowAction(id: String) {
        val action = id.removePrefix(ACTION_PREFIX)
        when {
            action.startsWith("vol:") -> {
                if (connection.api.stats().fixedVolume) {
                    DebugLog.log("control", "volume ignored, Fixed Volume is on")
                    return
                }
                val delta = action.removePrefix("vol:").toInt()
                val target = (connection.api.status().volume + delta).coerceIn(0, 100)
                DebugLog.log("control", "volume to $target")
                connection.api.setVolume(target)
            }
            action == "eq-off" -> {
                DebugLog.log("control", "EQ off")
                connection.api.eqOff()
            }
            action.startsWith("eq:") -> {
                val name = action.removePrefix("eq:")
                DebugLog.log("control", "EQ $name")
                connection.api.eqLoad(name)
                connection.api.eq().takeIf { !it.enabled }?.let { connection.api.eqLoad(name) }
            }
        }
    }

    @Synchronized
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
            // Android Auto may have asked for the list before the WiiM joined the hotspot.
            if (!before.reachable || before.presets != presets) main.post { onBrowseChanged?.invoke() }
            if (!before.reachable) {
                main.post {
                    connectedAction?.let { action ->
                        connectedAction = null
                        action(isTrackSource(snapshot))
                    }
                }
            }
            // The stream format arrives a few seconds after a preset change, so this can fire twice.
            val infoKey = listOf(
                status.status, status.vendor, status.volume, status.muted,
                meta?.subtitle, meta?.sampleRate, meta?.bitDepth, meta?.bitRate,
            )
            if (infoKey != lastInfoKey) {
                lastInfoKey = infoKey
                main.post { onInfoChanged?.invoke() }
            }
            // Follow stations changed from the phone or WiiM app, so blank names keep their place.
            val pending = presets.firstOrNull { it.number == pendingPreset }
            if (pending != null && pending.name.equals(meta?.subtitle, ignoreCase = true)) pendingPreset = null
            if (pendingPreset == null && status.status == "play") {
                presets.getOrNull(matchPreset(snapshot))?.let { lastPreset = it.number }
            }
        } catch (e: JSONException) {
            // The network is fine if device info still answers: only the WiiM's player has stalled.
            if (runCatching { connection.api.stats() }.isSuccess) {
                if (!snapshot.playerBusy) DebugLog.log("wiim", "connected, but its player is not answering")
                snapshot = snapshot.copy(reachable = true, playerBusy = true)
            } else {
                snapshot = snapshot.copy(reachable = false)
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
        val trackBased = isTrackSource(current)
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
                // Radio stays at a constant 0. Resetting a moving position makes Android Auto rescroll the queue.
                if (playing && trackBased) PositionSupplier.getExtrapolating(position, 1f)
                else PositionSupplier.getConstant(position),
            )
            // Repeat-all makes next/previous always available, including on a single live item.
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setMaxSeekToPreviousPositionMs(ALWAYS_PREVIOUS_MS)
            .build()
    }

    /**
     * The queue keeps the same shape in every state: all presets, then one row for anything else.
     * Replacing it makes Android Auto scroll back to the top.
     */
    private fun buildPlaylist(current: Snapshot): Pair<List<MediaItemData>, Int> {
        val status = current.status
        val presetIndex = matchPreset(current)
        val duration = if (presetIndex >= 0) null else status?.durationMs?.takeIf { it > 0 }
        val other = MediaMetadata.Builder()
            .setTitle("Other source")
            .setIsBrowsable(false)
            .setIsPlayable(false)
            .build()
        val otherItem = MediaItem.Builder().setMediaId(LIVE_ID).setMediaMetadata(other).build()
        val otherData = MediaItemData.Builder(LIVE_ID)
            .setMediaItem(otherItem)
            .setMediaMetadata(other)
            .setDurationUs(duration?.let { it * 1000 } ?: C.TIME_UNSET)
            .setIsSeekable(duration != null)
            .setIsDynamic(duration == null)
            .setLiveConfiguration(if (duration == null) LIVE_STREAM else null)
            .build()

        // Queue items never change. LiveMetadataPlayer reports the live song separately.
        val items = current.presets.map { preset ->
            val station = presetMetadata(preset)
            val id = presetId(preset.number)
            val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(station).build()
            MediaItemData.Builder(id)
                .setMediaItem(item)
                .setMediaMetadata(station)
                .setIsSeekable(false)
                // Radio has no length, so mark it live instead of showing a 0:00 track.
                .setIsDynamic(true)
                .setLiveConfiguration(LIVE_STREAM)
                .build()
        }
        return (items + otherData) to if (presetIndex >= 0) presetIndex else items.size
    }

    /** Keeps the real queue during a command, so a tapped row never flashes a one-item queue. */
    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        val real = getState()
        return suggestedPlaceholderState.buildUpon()
            .setPlaylist(real.playlist)
            .setCurrentMediaItemIndex(real.currentMediaItemIndex)
            .build()
    }

    private fun matchPreset(current: Snapshot): Int {
        val pending = pendingPreset
        if (pending != null) {
            if (SystemClock.elapsedRealtime() < pendingUntil) {
                val index = current.presets.indexOfFirst { it.number == pending }
                if (index >= 0) return index
            } else {
                pendingPreset = null
            }
        }
        return matchPresetIndex(current.presets, current.status, current.meta, lastPreset)
    }

    /** Some radio streams report a length, so a matched preset always counts as radio. */
    private fun isTrackSource(current: Snapshot) =
        current.status?.isTrackBased == true && matchPreset(current) < 0

    fun nowPlaying(): MediaMetadata = liveMetadata(snapshot)

    private fun publishNowPlaying(force: Boolean) {
        val metadata = nowPlaying()
        val key = currentMediaItemIndex to metadata
        if (force || key != lastPublished) {
            lastPublished = key
            onNowPlaying?.invoke(metadata)
        }
    }

    private fun liveMetadata(current: Snapshot): MediaMetadata {
        if (!current.reachable) {
            val secondsLeft = (searchUntil - SystemClock.elapsedRealtime() + 999) / 1000
            return MediaMetadata.Builder()
                .setTitle(if (secondsLeft > 0) "Connecting to WiiM... $secondsLeft s" else "WiiM not found")
                .setArtist(
                    if (secondsLeft > 0) "Waiting for it to join the hotspot"
                    else "Check the hotspot, then press play to search again",
                )
                .setAlbumTitle(connection.host)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        }
        if (current.playerBusy) {
            return MediaMetadata.Builder()
                .setTitle("WiiM player not responding")
                .setArtist("Connected. Restart the WiiM if this stays")
                .setAlbumTitle(connection.host)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        }
        val meta = current.meta
        val status = current.status
        if (status?.isIdle == true && meta?.title.isNullOrEmpty() && status.fallbackTitle.isEmpty()) {
            return MediaMetadata.Builder()
                .setTitle("WiiM connected")
                .setArtist("Press play or choose a preset")
                .setAlbumTitle(connection.host)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        }
        val title = meta?.title?.ifEmpty { null } ?: status?.fallbackTitle?.ifEmpty { null } ?: "WiiM"
        val performer = meta?.artist?.ifEmpty { null } ?: status?.fallbackArtist.orEmpty()
        // Android Auto has no line above the title, so the format leads the line below it.
        val note = volumeNote.takeIf { SystemClock.elapsedRealtime() < volumeNoteUntil }
        val artist = note ?: listOfNotNull(meta?.formatLine?.ifEmpty { null }, performer.ifEmpty { null })
            .joinToString("  |  ")
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

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && SystemClock.elapsedRealtime() < ignorePlayUntil) {
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady && !snapshot.reachable) {
            startConnecting()
            return Futures.immediateVoidFuture()
        }
        // During Spotify Connect the Spotify app is the remote, so don't take audio focus from it.
        if (playWhenReady && !isTrackSource(snapshot)) onUserPlayback?.invoke()
        return command {
            DebugLog.log("control", if (playWhenReady) "play" else "pause")
            if (!playWhenReady) {
                connection.api.pause()
            } else {
                val preset = lastPreset ?: snapshot.presets.firstOrNull()?.number
                if (snapshot.status?.isIdle == true && preset != null) connection.api.playPreset(preset)
                else connection.api.resume()
            }
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = snapshot
        val trackBased = isTrackSource(current)
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
        if (selected?.mediaId?.startsWith(ACTION_PREFIX) == true) {
            ignorePlayUntil = SystemClock.elapsedRealtime() + IGNORE_PLAY_MS
            val done = command { runRowAction(selected.mediaId) }
            done.addListener({ onBrowseChanged?.invoke() }, main::post)
            return done
        }
        val number = selected?.mediaId?.removePrefix(PRESET_PREFIX)?.toIntOrNull()
        return if (selected?.mediaId?.startsWith(PRESET_PREFIX) == true && number != null) {
            playPreset(number)
        } else {
            Futures.immediateVoidFuture()
        }
    }

    private fun playPreset(number: Int): ListenableFuture<*> {
        lastPreset = number
        pendingPreset = number
        pendingUntil = SystemClock.elapsedRealtime() + PENDING_PRESET_MS
        DebugLog.log("control", "preset $number")
        onUserPlayback?.invoke()
        return command(settleMs = 0) {
            // With quick repeated presses, only the newest preset is sent to the WiiM.
            if (pendingPreset == number) {
                connection.api.playPreset(number)
                Thread.sleep(PRESET_SETTLE_MS)
            }
        }
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    // Stopping the car session must not silence the WiiM unless the user pressed pause.
    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        pollIo.shutdown()
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
            main.post {
                done.set(Unit)
                // Runs after Media3 applies the new state, so the live song replaces the station.
                main.post { if (!released) publishNowPlaying(force = true) }
            }
        }
        return done
    }

    companion object {
        const val PRESET_PREFIX = "preset:"
        const val ACTION_PREFIX = "action:"
        private const val SEARCH_WINDOW_MS = 45_000L
        private const val IGNORE_PLAY_MS = 2_000L
        private const val VOLUME_NOTE_MS = 3_000L
        private const val LIVE_ID = "live"
        private const val TAG = "WiimPlayer"
        private const val POLL_MS = 2000L
        private const val PRESET_REFRESH_MS = 60_000L
        private const val COMMAND_SETTLE_MS = 150L
        private const val PRESET_SETTLE_MS = 800L
        private const val PENDING_PRESET_MS = 8000L
        private const val ALWAYS_PREVIOUS_MS = 24L * 60 * 60 * 1000
        private val LIVE_STREAM = MediaItem.LiveConfiguration.Builder().build()

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
