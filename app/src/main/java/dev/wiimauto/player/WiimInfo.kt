package dev.wiimauto.player

/** Live details for the "nerd" screens. A failed query only blanks its own rows. */
fun wiimInfoRows(connection: WiimConnection): List<Pair<String, String>> {
    val api = connection.api
    val started = System.nanoTime()
    val stats = runCatching { api.stats() }.getOrNull()
    val latencyMs = (System.nanoTime() - started) / 1_000_000
    val status = runCatching { api.status() }.getOrNull()
    val meta = runCatching { api.meta() }.getOrNull()
    val eq = runCatching { api.eq() }.getOrNull()
    val output = runCatching { api.output() }.getOrNull()

    fun value(text: String?, unit: String = "") =
        text?.takeIf { it.isNotEmpty() }?.let { "$it$unit" } ?: "unknown"

    return listOf(
        "Connection" to if (stats != null) "OK, $latencyMs ms to ${connection.host}" else "No reply from ${connection.host}",
        "Phone route" to connection.routeLabel,
        "Phone networks" to connection.phoneInterfaces().ifEmpty { "none" },
        "Stream" to listOf(
            meta?.sampleRate?.toIntOrNull()?.let { "%.1f kHz".format(it / 1000f) },
            meta?.bitRate?.takeIf { it.isNotEmpty() && it != "0" }?.let { "$it kbps" },
        ).filterNotNull().joinToString(", ").ifEmpty { "unknown" },
        "Source" to "${value(status?.vendor)}, ${value(status?.status)}",
        "Volume" to (status?.let { "${it.volume}%" + (if (stats?.fixedVolume == true) ", fixed" else "") + if (it.muted) ", muted" else "" } ?: "unknown"),
        "EQ" to (eq?.let { if (it.enabled) it.name else "Off" } ?: "unknown"),
        "Output" to (output?.label ?: "unknown"),
        "WiiM Wi-Fi" to "${value(stats?.network)}, ${value(stats?.ip)}",
        "Signal" to "${value(stats?.rssi, " dBm")}, SNR ${value(stats?.snr, " dB")}, noise ${value(stats?.noise, " dBm")}",
        "Wi-Fi link" to "${value(stats?.frequencyMhz, " MHz")}, ${value(stats?.linkMbps, " Mbps")}",
        "Internet" to when (stats?.internet) { true -> "Yes"; false -> "No"; null -> "unknown" },
        "Device" to "${value(stats?.name)}, ${value(stats?.model)}",
        "Firmware" to value(stats?.firmware),
    )
}
