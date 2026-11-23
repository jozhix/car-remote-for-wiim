package dev.wiimauto.player

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A log kept on the phone for car tests, where no debugger is connected. */
object DebugLog {
    private const val MAX_DAY_BYTES = 512 * 1024L
    private const val MAX_SHARE_CHARS = 100_000
    private const val KEEP_DAYS = 3
    private val DAY_FILE = Regex("""debug-(\d{4}-\d{2}-\d{2})\.log""")

    private var directory: File? = null
    private var lastPruneDay = ""
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        directory = context.filesDir
        prune(Date())
    }

    @Synchronized
    fun log(area: String, message: String) {
        Log.i("WiimAuto", "$area: $message")
        val folder = directory ?: return
        runCatching {
            val now = Date()
            val day = dayName(now)
            if (day != lastPruneDay) prune(now)
            val current = File(folder, "debug-$day.log")
            if (current.length() > MAX_DAY_BYTES) {
                current.writeText(current.readText().takeLast((MAX_DAY_BYTES / 2).toInt()))
            }
            current.appendText("${time.format(now)} [$area] $message\n")
        }
    }

    /** Keeps today and the two days before it. Unrecognised old log files are removed too. */
    fun isExpired(fileName: String, now: Date): Boolean {
        val day = DAY_FILE.matchEntire(fileName)?.groupValues?.get(1) ?: return true
        val oldest = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_MONTH, -(KEEP_DAYS - 1))
        }
        return day < dayName(oldest.time)
    }

    private fun prune(now: Date) {
        logFiles().filter { isExpired(it.name, now) }.forEach { it.delete() }
        lastPruneDay = dayName(now)
    }

    private fun logFiles(): List<File> =
        directory?.listFiles { file -> file.name.startsWith("debug") && file.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun dayName(date: Date) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    @Synchronized
    fun shareText(): String {
        prune(Date())
        val body = logFiles()
            .joinToString("") { it.readText() }
            .takeLast(MAX_SHARE_CHARS)
        return "Car Remote for WiiM ${BuildConfigInfo.version}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}\n" +
            "Interfaces: ${interfaces()}\n\n$body"
    }

    fun interfaces(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${nic.name}=${it.hostAddress}" }
            }
            .joinToString(", ")
    }.getOrDefault("unavailable")
}

private object BuildConfigInfo {
    const val version = "0.1.0"
}

class WiimApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.log("app", "started, interfaces: ${DebugLog.interfaces()}")

        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.log("crash", "${thread.name}: ${Log.getStackTraceString(error)}")
            fallback?.uncaughtException(thread, error)
        }

        val connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = report("available", network)
                override fun onLost(network: Network) = report("lost", network)
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                    report("link changed (${properties.interfaceName})", network)

                private fun report(event: String, network: Network) {
                    val caps = connectivity.getNetworkCapabilities(network)
                    val kind = when {
                        caps == null -> "unknown"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        else -> "other"
                    }
                    DebugLog.log("network", "$event $kind; interfaces: ${DebugLog.interfaces()}")
                }
            })
        }.onFailure { DebugLog.log("network", "callback failed: ${it.message}") }
    }
}
