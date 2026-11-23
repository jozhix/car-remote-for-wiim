package dev.wiimauto.player

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/** The entry point Android Auto binds to for browsing presets and for playback controls. */
@OptIn(UnstableApi::class)
class WiimMediaService : MediaLibraryService() {

    private lateinit var player: WiimPlayer
    private var session: MediaLibrarySession? = null
    private val browseIo = Executors.newSingleThreadExecutor()
    private lateinit var silence: SilentAudio
    private lateinit var carType: LiveData<Int>
    private var carConnected = false
    private var keyTestMode = false

    private val carObserver = Observer<Int> { type ->
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION
        val justConnected = connected && !carConnected
        // Checked before the silence starts, so a player already running here keeps the keys.
        val otherMusic = justConnected && getSystemService(AudioManager::class.java).isMusicActive
        carConnected = connected
        DebugLog.log("car", "Android Auto ${if (carConnected) "connected" else "not connected"} (type $type)")
        updateSilence()
        if (!connected) player.cancelWhenConnected()
        if (justConnected) {
            player.startConnecting()
            if (otherMusic) {
                DebugLog.log("steering", "another player is active, leaving the keys with it")
            } else {
                // Takes the car's media channel as soon as the WiiM is there, so keys work from the start.
                player.whenConnected { trackSource -> if (carConnected && !trackSource) silence.claimKeys() }
            }
        }
    }

    /** Silence runs only during Android Auto, so media keys work normally outside the car. */
    private fun updateSilence() {
        if (carConnected || keyTestMode) silence.start() else silence.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.action == ACTION_TEST_KEYS) {
            keyTestMode = true
            // Android Auto normally triggers this. Without it the session never publishes its buttons.
            session?.let { addSession(it) }
            updateSilence()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.log("service", "created")
        silence = SilentAudio(this)
        player = WiimPlayer(WiimConnection(this))
        player.onUserPlayback = { if (carConnected || keyTestMode) silence.claimKeys() }
        player.onBrowseChanged = {
            session?.let { library ->
                listOf(ROOT_ID, PRESETS_ID, SOUND_ID, INFO_ID).forEach { library.notifyChildrenChanged(it, CHILD_COUNT, null) }
            }
        }
        player.onInfoChanged = { session?.notifyChildrenChanged(INFO_ID, CHILD_COUNT, null) }
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                DebugLog.log("car", "queue changed: ${timeline.windowCount} items")
            }
        })
        session = MediaLibrarySession.Builder(this, LiveMetadataPlayer(player), LibraryCallback())
            .setMediaButtonPreferences(volumeButtons)
            .build()
        carType = CarConnection(this).type
        carType.observeForever(carObserver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        DebugLog.log("service", "destroyed")
        carType.removeObserver(carObserver)
        silence.stop()
        session?.release()
        session = null
        player.release()
        browseIo.shutdown()
        super.onDestroy()
    }

    private fun presetItems(): List<MediaItem> = player.presetsNow().map { preset ->
        MediaItem.Builder()
            .setMediaId(WiimPlayer.presetId(preset.number))
            .setMediaMetadata(WiimPlayer.presetMetadata(preset))
            .build()
    }

    private val volumeButtons by lazy {
        ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_VOLUME_DOWN)
                .setDisplayName("WiiM volume down")
                .setSessionCommand(VOLUME_DOWN)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_VOLUME_UP)
                .setDisplayName("WiiM volume up")
                .setSessionCommand(VOLUME_UP)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** Android Auto only lets a list row be tapped when it is marked playable. */
    private fun row(id: String, title: String, subtitle: String = "") = MediaItem.Builder()
        .setMediaId(WiimPlayer.ACTION_PREFIX + id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun soundItems(): List<MediaItem> {
        val eq = player.eqNow()
        val volume = player.volumeNow()?.let { "$it%" } ?: "unknown"
        val volumeNote = if (player.fixedVolumeNow() == true) "$volume, Fixed Volume is on (change on phone)" else "Now $volume"
        val presets = player.eqPresetsNow().map { name ->
            val active = eq?.enabled == true && eq.name == name
            row("eq:$name", if (active) "$name (on)" else name, "EQ preset")
        }
        return listOf(
            row("vol:-5", "Volume down", volumeNote),
            row("vol:5", "Volume up", volumeNote),
            row("eq-off", if (eq?.enabled == false) "EQ off (on)" else "EQ off", "Bypass the WiiM equalizer"),
        ) + presets
    }

    private fun infoItems(): List<MediaItem> =
        listOf(row("refresh", "Refresh", "Tap to read the latest values")) +
            player.infoRows().mapIndexed { index, (label, value) -> row("info:$index", label, value) }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            DebugLog.log("car", "controller connected: ${controller.packageName}")
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(VOLUME_DOWN)
                .add(VOLUME_UP)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(volumeButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                VOLUME_DOWN.customAction -> player.changeVolume(-VOLUME_STEP)
                VOLUME_UP.customAction -> player.changeVolume(VOLUME_STEP)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            DebugLog.log("car", "controller disconnected: ${controller.packageName}")
            super.onDisconnected(session, controller)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION") // The typed overload needs Android 13; the app supports 8.0.
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key?.action == KeyEvent.ACTION_DOWN) {
                DebugLog.log("steering", "${KeyEvent.keyCodeToString(key.keyCode)} from ${controllerInfo.packageName}")
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val metadata = MediaMetadata.Builder()
                .setTitle("Presets")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build()
            val root = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(metadata).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            browseIo.execute {
                val items = runCatching {
                    when (parentId) {
                        ROOT_ID -> listOf(folder(PRESETS_ID, "Presets"), folder(SOUND_ID, "Sound"), folder(INFO_ID, "Info"))
                        PRESETS_ID -> presetItems()
                        SOUND_ID -> soundItems()
                        INFO_ID -> infoItems()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                DebugLog.log("car", "browse $parentId by ${browser.packageName}: ${items.size} items")
                val paged = items.drop(page * pageSize).take(pageSize)
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            browseIo.execute {
                val item = presetItems().firstOrNull { it.mediaId == mediaId }
                result.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            }
            return result
        }

        // Items are resolved by preset id in WiimPlayer, so they need no stream URL here.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val PRESETS_ID = "presets"
        const val SOUND_ID = "sound"
        const val INFO_ID = "info"
        const val CHILD_COUNT = 40
        const val VOLUME_STEP = 5
        val VOLUME_DOWN = SessionCommand("dev.wiimauto.player.VOLUME_DOWN", Bundle.EMPTY)
        val VOLUME_UP = SessionCommand("dev.wiimauto.player.VOLUME_UP", Bundle.EMPTY)
        const val ACTION_TEST_KEYS = "dev.wiimauto.player.TEST_KEYS"
    }
}
