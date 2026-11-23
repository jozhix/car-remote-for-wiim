package dev.wiimauto.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class WiimApiTest {

    @Test
    fun parsesRadioStatusFromRealDevice() {
        val status = WiimApi.parseStatus(
            """{"vendor":"newTuneIn","status":"play","curpos":"163561","totlen":"0",
            "Title":"74616B65206D6520686F6D65","Artist":"4F6C6976696120526F647269676F","plicount":"0","vol":"100","mute":"0"}""",
        )
        assertTrue(status.isPlaying)
        assertFalse(status.isTrackBased)
        assertEquals("take me home", status.fallbackTitle)
        assertEquals("Olivia Rodrigo", status.fallbackArtist)
    }

    @Test
    fun treatsBufferingAsPlaying() {
        assertTrue(WiimApi.parseStatus("""{"status":"load"}""").isPlaying)
        assertFalse(WiimApi.parseStatus("""{"status":"pause"}""").isPlaying)
    }

    @Test
    fun handlesIdleWiimMini() {
        val status = WiimApi.parseStatus(
            """{"status":"none","Title":"556E6B6E6F776E","Artist":"556E6B6E6F776E","vol":"1"}""",
        )
        assertTrue(status.isIdle)
        assertFalse(status.isPlaying)
        assertEquals("", status.fallbackTitle)
        assertEquals(null, WiimApi(WiimTransport { "Failed" }).meta())
    }

    @Test
    fun reconnectsOnlyToRememberedDevice() {
        val pro = "192.168.1.50" to DeviceInfo("Pro", "PRO", "", "WiiM_Pro")
        val mini = "192.168.43.20" to DeviceInfo("Mini", "MINI", "", "Muzo_Mini")

        assertEquals("192.168.43.20", pickDevice(listOf(pro, mini), "MINI"))
        assertEquals(null, pickDevice(listOf(pro), "MINI"))
        assertEquals("192.168.1.50", pickDevice(listOf(pro), null))
        assertEquals(null, pickDevice(listOf(pro, mini), null))
    }

    @Test
    fun testToneIsQuietStereo96k24Bit() {
        val wav = TestTone.wav()
        val buffer = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, buffer.getShort(22).toInt())
        assertEquals(96_000, buffer.getInt(24))
        assertEquals(24, buffer.getShort(34).toInt())
        assertEquals(wav.size - 44, buffer.getInt(40))

        fun sample(frame: Int, channel: Int): Int {
            val at = 44 + frame * 6 + channel * 3
            return (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8) or (wav[at + 2].toInt() shl 16)
        }
        val frames = (wav.size - 44) / 6
        val peak = (0 until frames).maxOf { maxOf(kotlin.math.abs(sample(it, 0)), kotlin.math.abs(sample(it, 1))) }
        assertTrue(peak <= 8_388_607 * 0.064)
        // During the first tone only the left channel carries signal.
        assertEquals(0, (96_000 until 240_000).maxOf { kotlin.math.abs(sample(it, 1)) })
    }

    @Test
    fun parsesAudioByteRanges() {
        assertEquals(0 to 99, TestToneServer.parseRange(null, 100))
        assertEquals(10 to 99, TestToneServer.parseRange("bytes=10-", 100))
        assertEquals(10 to 20, TestToneServer.parseRange("bytes=10-20", 100))
    }

    @Test
    fun keepsPresetWhenStationNameIsBlank() {
        val presets = listOf(
            Preset(1, "European Hit Radio", "newTuneIn", ""),
            Preset(3, "Radio Paradise", "RadioParadise", ""),
            Preset(7, "XO FM", "newTuneIn", ""),
        )
        fun status(vendor: String) = WiimApi.parseStatus("""{"status":"load","vendor":"$vendor"}""")
        fun meta(station: String) = TrackMeta("", "", "", station, "", "", "")

        assertEquals(2, matchPresetIndex(presets, status("newTuneIn"), meta("xo fm"), 1))
        assertEquals(2, matchPresetIndex(presets, status("newTuneIn"), meta(""), 7))
        assertEquals(1, matchPresetIndex(presets, status("RadioParadise"), meta(""), 7))
        assertEquals(-1, matchPresetIndex(presets, status("newTuneIn"), meta(""), null))
        assertEquals(-1, matchPresetIndex(presets, status("CustomPushUrl"), meta(""), 7))
    }

    @Test
    fun debugLogsExpireAfterThreeDays() {
        val now = java.util.GregorianCalendar(2026, java.util.Calendar.NOVEMBER, 23, 12, 0).time
        assertFalse(DebugLog.isExpired("debug-2026-11-23.log", now))
        assertFalse(DebugLog.isExpired("debug-2026-11-21.log", now))
        assertTrue(DebugLog.isExpired("debug-2026-11-20.log", now))
        assertTrue(DebugLog.isExpired("debug.old.log", now))
    }

    @Test
    fun recognisesWiimMiniProject() {
        val mini = WiimApi.parseDevice("""{"DeviceName":"Car audio","project":"Muzo_Mini"}""")
        val other = WiimApi.parseDevice("""{"DeviceName":"Speaker","project":"Other"}""")
        assertTrue(mini.isWiim)
        assertFalse(other.isWiim)
    }

    @Test
    fun cleansMetadataAndUpgradesArtwork() {
        val meta = WiimApi.parseMeta(
            """{"metaData":{"album":"unknow","title":"Song ","subtitle":"European Hit Radio",
            "artist":"Artist","albumArtURI":"http://cdn-radiotime-logos.tunein.com/s80816g.png"}}""",
        )
        assertEquals("", meta.album)
        assertEquals("Song", meta.title)
        assertEquals("https://cdn-radiotime-logos.tunein.com/s80816g.png", meta.artUrl)
    }

    @Test
    fun parsesPresets() {
        val presets = WiimApi.parsePresets(
            """{"preset_num":1,"preset_list":[{"number":3,"name":"Radio Paradise","source":"RadioParadise",
            "picurl":"https://img.radioparadise.com/covers/l/B000059MEM.jpg"}]}""",
        )
        assertEquals(3, presets.single().number)
        assertEquals("Radio Paradise", presets.single().name)
    }

    /** Read-only. Runs only with -Pwiim.host=<ip>, and never changes playback. */
    @Test
    fun readsLiveDevice() {
        val host = System.getProperty("wiim.host").orEmpty()
        assumeTrue(host.isNotEmpty())
        val api = WiimApi(liveTransport(host))

        assertTrue(api.device().isWiim)
        assertTrue(api.status().status.isNotEmpty())
        api.meta()
        assertTrue(api.presets().isNotEmpty())
    }

    private fun liveTransport(host: String) = WiimTransport { command ->
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAny), null) }
        val connection = URL("https://$host/httpapi.asp?command=$command").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        connection.setHostnameVerifier { name, _ -> name == host }
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.inputStream.bufferedReader().use { it.readText() }
    }
}
