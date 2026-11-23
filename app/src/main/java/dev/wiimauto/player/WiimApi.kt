package dev.wiimauto.player

import org.json.JSONObject

/** Sends one httpapi.asp command and returns the raw response body. */
fun interface WiimTransport {
    fun get(command: String): String
}

data class DeviceInfo(val name: String, val uuid: String, val firmware: String, val project: String) {
    /** The WiiM Mini reports its project as "Muzo_Mini". */
    val isWiim get() = listOf(project, name).any {
        it.contains("wiim", ignoreCase = true) || it.contains("muzo", ignoreCase = true)
    }
}

/**
 * Picks the IP to reconnect to. A remembered device is matched by UUID only, so the app never
 * silently switches to a different WiiM. With nothing remembered, a lone WiiM is safe to adopt.
 */
fun pickDevice(found: List<Pair<String, DeviceInfo>>, savedUuid: String?): String? =
    if (savedUuid.isNullOrEmpty()) found.singleOrNull()?.first
    else found.firstOrNull { it.second.uuid == savedUuid }?.first

/**
 * Finds the playing preset. The station name is blank while a stream loads and always blank for
 * Radio Paradise, so fall back to a unique source, then to the last known preset of that source.
 */
fun matchPresetIndex(
    presets: List<Preset>,
    status: PlayerStatus?,
    meta: TrackMeta?,
    lastPreset: Int?,
): Int {
    if (status == null) return -1
    val sameSource = presets.indices.filter { presets[it].source == status.vendor }
    if (sameSource.isEmpty()) return -1
    val station = meta?.subtitle.orEmpty()
    return sameSource.firstOrNull { station.isNotEmpty() && presets[it].name.equals(station, true) }
        ?: sameSource.singleOrNull()
        ?: sameSource.firstOrNull { presets[it].number == lastPreset }
        ?: -1
}

data class PlayerStatus(
    val status: String,
    val vendor: String,
    val positionMs: Long,
    val durationMs: Long,
    val playlistCount: Int,
    val volume: Int,
    val muted: Boolean,
    val fallbackTitle: String,
    val fallbackArtist: String,
) {
    /** The WiiM reports "load" while a station is buffering. */
    val isPlaying get() = status == "play" || status == "load" || status == "loading"

    /** Nothing is loaded, so resume has no effect. An idle Mini reports "none". */
    val isIdle get() = status == "stop" || status == "none" || status.isEmpty()

    /** Radio has no track list, so next/previous steps through presets instead. */
    val isTrackBased get() = durationMs > 0 || playlistCount > 1
}

data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String,
    val subtitle: String,
    val artUrl: String,
    val sampleRate: String,
    val bitDepth: String,
)

data class Preset(val number: Int, val name: String, val source: String, val artUrl: String)

class WiimApi(private val transport: WiimTransport) {

    fun device(): DeviceInfo = parseDevice(transport.get("getStatusEx"))
    fun status(): PlayerStatus = parseStatus(transport.get("getPlayerStatus"))

    /** Null when nothing is loaded: an idle Mini answers with the plain text "Failed". */
    fun meta(): TrackMeta? {
        val body = transport.get("getMetaInfo")
        return if (body.trimStart().startsWith("{")) parseMeta(body) else null
    }

    fun presets(): List<Preset> = parsePresets(transport.get("getPresetInfo"))

    fun pause() = send("setPlayerCmd:pause")
    fun resume() = send("setPlayerCmd:resume")
    fun next() = send("setPlayerCmd:next")
    fun previous() = send("setPlayerCmd:prev")
    fun seekTo(seconds: Long) = send("setPlayerCmd:seek:$seconds")
    fun playPreset(number: Int) = send("MCUKeyShortClick:$number")
    fun playUrl(url: String) = send("setPlayerCmd:play:$url")

    private fun send(command: String) {
        transport.get(command)
    }

    companion object {
        fun parseDevice(body: String): DeviceInfo {
            val json = JSONObject(body)
            return DeviceInfo(
                name = json.optString("DeviceName"),
                uuid = json.optString("uuid"),
                firmware = json.optString("firmware"),
                project = json.optString("project"),
            )
        }

        fun parseStatus(body: String): PlayerStatus {
            val json = JSONObject(body)
            return PlayerStatus(
                status = json.optString("status"),
                vendor = json.optString("vendor"),
                positionMs = json.optString("curpos").toLongOrNull() ?: 0,
                durationMs = json.optString("totlen").toLongOrNull() ?: 0,
                playlistCount = json.optString("plicount").toIntOrNull() ?: 0,
                volume = json.optString("vol").toIntOrNull() ?: 0,
                muted = json.optString("mute") == "1",
                fallbackTitle = clean(decodeHex(json.optString("Title"))),
                fallbackArtist = clean(decodeHex(json.optString("Artist"))),
            )
        }

        fun parseMeta(body: String): TrackMeta {
            val meta = JSONObject(body).getJSONObject("metaData")
            return TrackMeta(
                title = clean(meta.optString("title")),
                artist = clean(meta.optString("artist")),
                album = clean(meta.optString("album")),
                subtitle = clean(meta.optString("subtitle")),
                artUrl = secureUrl(meta.optString("albumArtURI")),
                sampleRate = clean(meta.optString("sampleRate")),
                bitDepth = clean(meta.optString("bitDepth")),
            )
        }

        fun parsePresets(body: String): List<Preset> {
            val list = JSONObject(body).optJSONArray("preset_list") ?: return emptyList()
            return (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                Preset(
                    number = item.getInt("number"),
                    name = clean(item.optString("name")),
                    source = clean(item.optString("source")),
                    artUrl = secureUrl(item.optString("picurl")),
                )
            }
        }

        /** The app blocks cleartext traffic; the artwork CDNs all serve HTTPS too. */
        fun secureUrl(url: String): String {
            val value = clean(url)
            return if (value.startsWith("http://")) "https://" + value.removePrefix("http://") else value
        }

        fun clean(value: String): String {
            val text = value.trim()
            return if (text.equals("unknow", true) || text.equals("unknown", true)) "" else text
        }

        /** getPlayerStatus reports text as hex-encoded UTF-8. */
        fun decodeHex(value: String): String {
            if (value.isEmpty() || value.length % 2 != 0) return value
            if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return value
            val bytes = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
