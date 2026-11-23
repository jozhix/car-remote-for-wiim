package dev.wiimauto.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Reaches the WiiM over whichever route works. In the car the phone can have several networks at
 * once (adapter Wi-Fi, hotspot, mobile data) and Android will not pick the right one by itself.
 */
class WiimConnection(context: Context) : WiimTransport {

    private val prefs = context.applicationContext.getSharedPreferences("wiim", Context.MODE_PRIVATE)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var route: Route? = null

    val api = WiimApi(this)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) {
            if (value != host) {
                prefs.edit().putString(KEY_HOST, value).remove(KEY_PIN).remove(KEY_UUID).remove(KEY_LAST_PRESET).apply()
            }
            route = null
        }

    /** Shared by the phone and car screens, because WiiM Radio stations report no name. */
    var lastPreset: Int?
        get() = prefs.getInt(KEY_LAST_PRESET, 0).takeIf { it > 0 }
        set(value) {
            if (value != lastPreset) prefs.edit().putInt(KEY_LAST_PRESET, value ?: 0).apply()
        }

    override fun get(command: String): String {
        val startedHost = host
        val body = try {
            fetch(command)
        } catch (failure: IOException) {
            // A search may have moved the WiiM during this request, so try the new IP first.
            if (host == startedHost && !rediscover()) throw failure
            fetch(command)
        }
        discoveryDelay = DISCOVERY_INTERVAL_MS
        return body
    }

    /** The WiiM gets a new IP on the car hotspot, so look for the same device by UUID. */
    private fun rediscover(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val fast = now < fastSearchUntil
        val wait = if (fast) FAST_DISCOVERY_INTERVAL_MS else discoveryDelay
        if (now - lastDiscovery < wait || !discoveryLock.tryLock()) return false
        try {
            lastDiscovery = now
            // Search less often while the WiiM stays away, to save battery.
            if (!fast) discoveryDelay = minOf(discoveryDelay * 2, MAX_DISCOVERY_INTERVAL_MS)
            val savedUuid = prefs.getString(KEY_UUID, null)
            val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
            DebugLog.log("search", "saved IP $host failed; searching ${DebugLog.interfaces()}")
            val found = scan()
            DebugLog.log("search", "found: ${found.joinToString { "${it.second.name} ${it.first}" }.ifEmpty { "none" }}")
            val ip = pickDevice(found, savedUuid, known) ?: return false
            if (ip == host) return false
            val uuid = found.first { it.first == ip }.second.uuid
            DebugLog.log("search", "moved from $host to $ip")
            prefs.edit().putString(KEY_HOST, ip).apply {
                if (uuid != savedUuid) {
                    putString(KEY_UUID, uuid)
                    remove(KEY_LAST_PRESET)
                    // Each known WiiM keeps its own certificate pin.
                    val pin = prefs.getString(pinKey(uuid), null)
                    if (pin != null) putString(KEY_PIN, pin) else remove(KEY_PIN)
                }
            }.apply()
            route = null
            return true
        } catch (e: Exception) {
            DebugLog.log("search", "failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            discoveryLock.unlock()
        }
    }

    private fun learnDevice(network: Network?) {
        runCatching {
            val uuid = prefs.getString(KEY_UUID, null)
                ?: WiimApi.parseDevice(request(host, network, "getStatusEx", TIMEOUT_MS, pinned = true)).uuid
            if (uuid.isEmpty()) return
            val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
            if (uuid in known && prefs.contains(pinKey(uuid))) return
            prefs.edit()
                .putString(KEY_UUID, uuid)
                .putStringSet(KEY_KNOWN, known + uuid)
                .putString(pinKey(uuid), prefs.getString(KEY_PIN, null))
                .apply()
        }
    }

    /** Searches every few seconds for a while, because the WiiM joins the hotspot after Android Auto starts. */
    fun searchFast(durationMs: Long) {
        fastSearchUntil = SystemClock.elapsedRealtime() + durationMs
        lastDiscovery = -MAX_DISCOVERY_INTERVAL_MS
    }

    val routeLabel: String get() = route?.label ?: "none"

    fun phoneInterfaces(): String = localSubnets().joinToString { "${it.interfaceName} ${it.address}" }

    private fun fetch(command: String): String {
        if (host.isEmpty()) throw IOException("No WiiM selected")
        route?.let { known ->
            try {
                val body = request(host, known.network, command, TIMEOUT_MS, pinned = true)
                learnDevice(known.network)
                return body
            } catch (_: IOException) {
                route = null
            }
        }
        var failure: IOException? = null
        for (candidate in routes()) {
            try {
                val body = request(host, candidate.network, command, TIMEOUT_MS, pinned = true)
                route = candidate
                DebugLog.log("route", "$host reachable via ${candidate.label}")
                learnDevice(candidate.network)
                return body
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("No network route available")
    }

    fun diagnose(): String = buildString {
        appendLine("Target: ${host.ifEmpty { "none selected" }}")
        appendLine("Active route: ${route?.label ?: "none yet"}")
        appendLine()
        for (candidate in if (host.isEmpty()) emptyList() else routes()) {
            val started = System.nanoTime()
            val result = try {
                val device = WiimApi.parseDevice(request(host, candidate.network, "getStatusEx", TIMEOUT_MS, pinned = true))
                "OK ${(System.nanoTime() - started) / 1_000_000} ms, ${device.name}"
            } catch (e: Exception) {
                "FAIL ${e.javaClass.simpleName}: ${e.message}"
            }
            appendLine("${candidate.label}: $result")
        }
        appendLine()
        appendLine("Local interfaces:")
        localSubnets().forEach { appendLine("  ${it.interfaceName}: ${it.address}") }
    }

    /** Finds WiiM devices on every local /24, because the IP changes on a phone hotspot. */
    fun scan(): List<Pair<String, DeviceInfo>> {
        val networks = routes().associateBy { it.interfaceName }
        val pool = Executors.newFixedThreadPool(SCAN_THREADS)
        try {
            val jobs = localSubnets().flatMap { subnet ->
                val prefix = subnet.address.substringBeforeLast('.')
                val network = networks[subnet.interfaceName]?.network
                (1..254).map { "$prefix.$it" }.filter { it != subnet.address }.map { ip ->
                    Callable {
                        try {
                            // A quick port check keeps the scan fast, so real devices can get a
                            // full timeout for the slower TLS request.
                            if (!isPortOpen(ip, network)) return@Callable null
                            val body = request(ip, network, "getStatusEx", TIMEOUT_MS, pinned = false)
                            val device = WiimApi.parseDevice(body)
                            if (device.isWiim) ip to device else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            return pool.invokeAll(jobs).mapNotNull { it.get() }.distinctBy { it.second.uuid }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The phone's own address on the WiiM's subnet, which the WiiM can connect back to. */
    fun localIpFor(target: String): String? {
        val prefix = target.substringBeforeLast('.')
        return localSubnets().firstOrNull { it.address.substringBeforeLast('.') == prefix }?.address
    }

    private fun isPortOpen(ip: String, network: Network?): Boolean =
        try {
            (network?.socketFactory?.createSocket() ?: Socket()).use {
                it.connect(InetSocketAddress(ip, HTTPS_PORT), SCAN_CONNECT_TIMEOUT_MS)
            }
            true
        } catch (_: IOException) {
            false
        }

    private fun routes(): List<Route> {
        val bound = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            val name = connectivity.getLinkProperties(network)?.interfaceName ?: "unknown"
            Route(network, name, "Network $name")
        }
        // Unbound comes last-resort for Wi-Fi but is the only route to hotspot clients.
        return bound + Route(null, null, "System default / hotspot")
    }

    private fun localSubnets(): List<Subnet> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") }
            .flatMap { nic ->
                nic.interfaceAddresses
                    .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
                    .map { Subnet(nic.name, it.address.hostAddress.orEmpty()) }
            }

    private fun request(ip: String, network: Network?, command: String, timeoutMs: Int, pinned: Boolean): String {
        val url = URL("https://$ip/httpapi.asp?command=$command")
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        connection.sslSocketFactory = sslContext(pinned).socketFactory
        connection.setHostnameVerifier { hostname, _ -> hostname == ip }
        connection.connectTimeout = timeoutMs
        // A playing Mini can take over 3 s to answer player commands on a healthy network.
        connection.readTimeout = if (pinned) READ_TIMEOUT_MS else timeoutMs
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sslContext(pinned: Boolean): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(if (pinned) pinningManager else scanManager), SecureRandom())
        }

    /** WiiM uses a self-signed certificate, so remember the first one seen for the saved device. */
    private val pinningManager = object : TrustAllDates() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val actual = fingerprint(chain.first())
            val expected = prefs.getString(KEY_PIN, null)
            if (expected == null) {
                prefs.edit().putString(KEY_PIN, actual).apply()
            } else if (expected != actual) {
                throw CertificateException("WiiM certificate changed")
            }
        }
    }

    /** Used only to read a candidate's public status during a scan. */
    private val scanManager = object : TrustAllDates() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }

    @SuppressLint("CustomX509TrustManager")
    private abstract class TrustAllDates : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private data class Route(val network: Network?, val interfaceName: String?, val label: String)
    private data class Subnet(val interfaceName: String, val address: String)

    companion object {
        const val DEFAULT_HOST = ""
        private const val KEY_HOST = "host"
        private const val KEY_PIN = "cert_pin"
        private const val KEY_UUID = "device_uuid"
        private const val KEY_KNOWN = "known_uuids"
        private const val KEY_LAST_PRESET = "last_preset"
        private const val DISCOVERY_INTERVAL_MS = 15_000L
        private const val FAST_DISCOVERY_INTERVAL_MS = 3_000L
        @Volatile private var fastSearchUntil = 0L

        private fun pinKey(uuid: String) = "cert_pin_$uuid"

        // Shared because the phone screen and the Android Auto service each have a connection.
        private val discoveryLock = ReentrantLock()
        private const val MAX_DISCOVERY_INTERVAL_MS = 120_000L
        @Volatile private var lastDiscovery = -MAX_DISCOVERY_INTERVAL_MS
        @Volatile private var discoveryDelay = DISCOVERY_INTERVAL_MS
        private const val TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 9000
        private const val HTTPS_PORT = 443
        private const val SCAN_CONNECT_TIMEOUT_MS = 600
        private const val SCAN_THREADS = 32
    }
}
