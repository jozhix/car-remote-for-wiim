package dev.wiimauto.player

import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
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

    override fun onCreate() {
        super.onCreate()
        DebugLog.log("service", "created")
        player = WiimPlayer(WiimConnection(this))
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                DebugLog.log("car", "queue changed: ${timeline.windowCount} items")
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        DebugLog.log("service", "destroyed")
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

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            DebugLog.log("car", "controller connected: ${controller.packageName}")
            return super.onConnect(session, controller)
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
                val items = if (parentId == ROOT_ID) presetItems() else emptyList()
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
    }
}
