package dev.wiimauto.player

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A quiet channel check: a low tone on the left, then a higher tone on the right. */
object TestTone {
    const val SAMPLE_RATE = 96_000
    const val BIT_DEPTH = 24
    const val LEVEL_DB = -24.0
    const val DURATION_MS = 5_500L

    private const val LEAD_SECONDS = 0.5
    private const val TONE_SECONDS = 2.0
    private const val GAP_SECONDS = 0.5
    private const val FADE_SECONDS = 0.05
    private const val LEFT_HZ = 440.0
    private const val RIGHT_HZ = 660.0
    private const val HEADER_BYTES = 44
    private const val FRAME_BYTES = 6

    fun wav(): ByteArray {
        val frames = (SAMPLE_RATE * DURATION_MS / 1000).toInt()
        val dataBytes = frames * FRAME_BYTES
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
        out.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_BYTES)
        out.putShort(FRAME_BYTES.toShort()).putShort(BIT_DEPTH.toShort())
        out.put("data".toByteArray()).putInt(dataBytes)

        val rightStart = LEAD_SECONDS + TONE_SECONDS + GAP_SECONDS
        for (frame in 0 until frames) {
            val time = frame.toDouble() / SAMPLE_RATE
            putSample(out, tone(time, LEAD_SECONDS, LEFT_HZ))
            putSample(out, tone(time, rightStart, RIGHT_HZ))
        }
        return out.array()
    }

    private fun tone(time: Double, start: Double, hz: Double): Double {
        val offset = time - start
        if (offset < 0 || offset >= TONE_SECONDS) return 0.0
        val edge = min(offset, TONE_SECONDS - offset)
        val fade = if (edge < FADE_SECONDS) (1 - cos(PI * edge / FADE_SECONDS)) / 2 else 1.0
        return 10.0.pow(LEVEL_DB / 20) * fade * sin(2 * PI * hz * offset)
    }

    private fun putSample(out: ByteBuffer, value: Double) {
        val sample = (value * 8_388_607).roundToInt()
        out.put(sample.toByte()).put((sample shr 8).toByte()).put((sample shr 16).toByte())
    }
}

/** Serves one WAV file to the WiiM only, from the phone, so no internet is needed. */
class TestToneServer(
    private val audio: ByteArray,
    bindIp: String,
    private val clientIp: String,
) : Closeable {

    private val server = ServerSocket(0, 4, InetAddress.getByName(bindIp))
    private val path = "/${UUID.randomUUID()}.wav"

    val url = "http://$bindIp:${server.localPort}$path"

    init {
        thread(isDaemon = true, name = "test-tone") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (socket.inetAddress.hostAddress == clientIp) {
                    thread(isDaemon = true) { socket.use { serve(it) } }
                } else {
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val request = reader.readLine().orEmpty().split(' ')
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }

            val output = socket.getOutputStream()
            if (request.size < 2 || request[1] != path || request[0] !in listOf("GET", "HEAD")) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@runCatching
            }

            val (start, end) = parseRange(range, audio.size)
            val partial = range != null
            val header = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\n")
                append("Content-Length: ${end - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${audio.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            if (request[0] == "GET") output.write(audio, start, end - start + 1)
            output.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000

        fun parseRange(range: String?, size: Int): Pair<Int, Int> {
            val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
                ?: return 0 to size - 1
            val start = match.groupValues[1].toIntOrNull()?.coerceIn(0, size - 1) ?: 0
            val end = match.groupValues[2].toIntOrNull()?.coerceIn(start, size - 1) ?: (size - 1)
            return start to end
        }
    }
}
