package dev.wiimauto.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Reports the live song separately from the queue. Media3 treats any change to a queue item as a
 * new queue, and Android Auto then scrolls its list back to the top.
 */
@OptIn(UnstableApi::class)
class LiveMetadataPlayer(private val wiim: WiimPlayer) : ForwardingPlayer(wiim) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    init {
        wiim.onNowPlaying = { metadata -> listeners.forEach { it.onMediaMetadataChanged(metadata) } }
    }

    override fun getMediaMetadata(): MediaMetadata = wiim.nowPlaying()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }
}
