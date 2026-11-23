package dev.wiimauto.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Phone remote styled as a car radio faceplate, plus the connection test and WiiM finder. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val playerIo = Executors.newSingleThreadExecutor()
    private val toolsIo = Executors.newSingleThreadExecutor()
    private val artIo = Executors.newFixedThreadPool(3)
    private val artCache = LruCache<String, Bitmap>(24)

    private lateinit var connection: WiimConnection

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var artView: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var stationText: TextView
    private lateinit var previousButton: ImageButton
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var presetBank: LinearLayout
    private lateinit var presetName: TextView
    private lateinit var hostInput: EditText
    private lateinit var testButton: Button
    private lateinit var findButton: Button
    private lateinit var testSoundButton: Button
    private lateinit var toolOutput: TextView

    private var presetKeys: List<PresetKey> = emptyList()

    @Volatile private var reachable = false
    @Volatile private var status: PlayerStatus? = null
    @Volatile private var meta: TrackMeta? = null
    @Volatile private var presets: List<Preset> = emptyList()
    @Volatile private var deviceName: String? = null
    private var lastPreset: Int?
        get() = connection.lastPreset
        set(value) {
            connection.lastPreset = value
        }
    @Volatile private var eq: EqState? = null
    @Volatile private var output: AudioOutput? = null
    @Volatile private var soundReadAt = 0L
    @Volatile private var fixedVolume: Boolean? = null
    @Volatile private var playerBusy = false
    @Volatile private var showingInfo = false
    private var infoKey: List<Any?>? = null
    private lateinit var fixedVolumeButton: Button
    private var draggingVolume = false
    private lateinit var volumeLabel: TextView
    private lateinit var volumeSlider: SeekBar
    private lateinit var eqButton: Button
    private lateinit var outputButton: Button
    private var started = false

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            playerIo.execute {
                refresh()
                main.post {
                    render()
                    if (started) main.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = WiimConnection(this)
        window.statusBarColor = DASH
        window.navigationBarColor = DASH
        setContentView(buildScreen())
        render()
        if (connection.host.isEmpty()) find()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        main.post(poll)
    }

    override fun onStop() {
        started = false
        main.removeCallbacks(poll)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        playerIo.shutdownNow()
        toolsIo.shutdownNow()
        artIo.shutdownNow()
        super.onDestroy()
    }

    // Screen

    private fun buildScreen(): View {
        val margin = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, dp(12), margin, dp(24))
            isFocusableInTouchMode = true
            addView(buildStatusRow(), matchWrap())
            addView(buildDisplay(), matchWrap(top = 12))
            addView(buildTransport(), matchWrap(top = 18))
            presetBank = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Room for the lit key to sit lower, like a pressed radio button.
                setPadding(0, 0, 0, dp(3))
                clipToPadding = false
            }
            addView(presetBank, matchWrap(top = 18))
            presetName = text(15f, PAPER).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(presetName, matchWrap(top = 10))
            addView(buildSound(), matchWrap(top = 22))
            addView(buildConnection(), matchWrap(top = 14))
        }
        return ScrollView(this).apply {
            setBackgroundColor(DASH)
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
        }
    }

    private fun buildStatusRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        statusDot = View(context)
        addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        statusText = text(14f, MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(statusText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(9) })
    }

    private fun buildDisplay() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(PANEL, 18, GLASS_EDGE)
        setPadding(dp(12), dp(12), dp(14), dp(12))

        artView = ImageView(context).apply {
            // Station logos carry text at their edges, so show the whole image.
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(KEY, 10)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(artView, LinearLayout.LayoutParams(dp(128), dp(128)))

        val lines = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            titleText = text(23f, PHOSPHOR, condensed = true, bold = true).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.02f)
            }
            artistText = text(16f, PAPER).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            stationText = text(13f, MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleText, matchWrap())
            addView(artistText, matchWrap(top = 4))
            addView(stationText, matchWrap(top = 4))
        }
        addView(lines, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
    }

    private fun buildTransport() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        previousButton = transportButton(R.drawable.ic_previous, "Previous", false) { step(-1) }
        playButton = transportButton(R.drawable.ic_play, "Play", true) { playPause() }
        nextButton = transportButton(R.drawable.ic_next, "Next", false) { step(1) }
        addView(previousButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(playButton, LinearLayout.LayoutParams(dp(78), dp(78)).apply {
            marginStart = dp(26)
            marginEnd = dp(26)
        })
        addView(nextButton, LinearLayout.LayoutParams(dp(64), dp(64)))
    }

    private fun buildSound() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 14)
        setPadding(dp(14), dp(12), dp(14), dp(14))

        volumeLabel = text(16f, PAPER, bold = true).apply { text = "WiiM volume" }
        addView(volumeLabel, matchWrap())
        volumeSlider = SeekBar(context).apply {
            max = 100
            contentDescription = "WiiM volume"
            progressTintList = ColorStateList.valueOf(PHOSPHOR)
            thumbTintList = ColorStateList.valueOf(PHOSPHOR)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) volumeLabel.text = "WiiM volume $value%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    draggingVolume = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    draggingVolume = false
                    val value = bar.progress
                    DebugLog.log("phone", "volume $value")
                    command { connection.api.setVolume(value) }
                }
            })
        }
        addView(volumeSlider, matchWrap(top = 6))
        fixedVolumeButton = toolButton("Fixed volume") { toggleFixedVolume() }
        addView(fixedVolumeButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            eqButton = toolButton("EQ") { chooseEq() }
            outputButton = toolButton("Output") { chooseOutput() }
            addView(eqButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            addView(outputButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        }
        addView(buttons, matchWrap(top = 8))
        addView(
            toolButton("Show technical info") { showTechnicalInfo(quiet = false) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) },
        )
    }

    /** Stays live: once shown, it reloads whenever the station, format or settings change. */
    private fun showTechnicalInfo(quiet: Boolean) {
        if (!quiet) showOutput("Reading WiiM details...")
        toolsIo.execute {
            val info = runCatching {
                wiimInfoRows(connection).joinToString("\n") { (label, value) -> "$label: $value" }
            }.getOrElse { "Failed: ${it.message}" }
            main.post {
                // Another tool may have replaced the output while this was loading.
                if (!isDestroyed && (!quiet || showingInfo)) showOutput(info, technicalInfo = true)
            }
        }
    }

    /** Turning Fixed Volume on jumps the WiiM to 100%, so that direction asks first. */
    private fun toggleFixedVolume() {
        val fixed = fixedVolume ?: return
        val apply = {
            command {
                connection.api.setFixedVolume(!fixed)
                DebugLog.log("control", "Fixed Volume ${if (fixed) "off" else "on"}")
                soundReadAt = 0
            }
        }
        if (fixed) {
            apply()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Turn on Fixed Volume?")
            .setMessage("The WiiM will jump to 100%. Turn down your DSP or car volume first.")
            .setPositiveButton("Turn on") { _, _ -> apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseEq() {
        toolsIo.execute {
            val names = runCatching { connection.api.eqPresets() }.getOrDefault(emptyList())
            main.post {
                if (isDestroyed) return@post
                if (names.isEmpty()) {
                    showOutput("Could not read the EQ list from the WiiM.")
                    return@post
                }
                val labels = (listOf("EQ off") + names).toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("WiiM equalizer")
                    .setItems(labels) { _, index ->
                        DebugLog.log("phone", "EQ ${labels[index]}")
                        command {
                            if (index == 0) connection.api.eqOff() else connection.api.eqLoad(names[index - 1])
                            soundReadAt = 0
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** A wrong output silences the car, so this always asks first. */
    private fun chooseOutput() {
        val outputs = AudioOutput.entries
        AlertDialog.Builder(this)
            .setTitle("WiiM audio output")
            .setItems(outputs.map { it.label }.toTypedArray()) { _, index ->
                val choice = outputs[index]
                if (choice == output) return@setItems
                AlertDialog.Builder(this)
                    .setTitle("Switch to ${choice.label}?")
                    .setMessage("Sound stops on ${output?.label ?: "the current output"}. Your DSP must be connected to ${choice.label}.")
                    .setPositiveButton("Switch") { _, _ ->
                        DebugLog.log("phone", "output ${choice.label}")
                        command {
                            connection.api.setOutput(choice)
                            soundReadAt = 0
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildConnection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 14)
        setPadding(dp(14), dp(12), dp(14), dp(14))

        addView(text(16f, PAPER, bold = true).apply { text = "Connection" }, matchWrap())

        hostInput = EditText(context).apply {
            setText(connection.host)
            hint = "WiiM IP address"
            setHintTextColor(MUTED)
            setTextColor(PAPER)
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            background = rounded(DASH, 9, KEY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            contentDescription = "WiiM IP address"
        }
        addView(hostInput, matchWrap(top = 10))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            testButton = toolButton("Test connection") { test() }
            findButton = toolButton("Find WiiM") { find() }
            addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            addView(findButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        }
        addView(buttons, matchWrap(top = 10))

        testSoundButton = toolButton("Play test sound") { playTestSound() }
        addView(testSoundButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        addView(
            toolButton("Share debug log") { shareDebugLog() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) },
        )

        toolOutput = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(PAPER)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        addView(toolOutput, matchWrap(top = 12))
    }

    private fun rebuildPresetKeys() {
        presetBank.removeAllViews()
        presetKeys = presets.mapIndexed { index, preset ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(DASH, 5)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val number = text(15f, MUTED, condensed = true, bold = true).apply {
                text = preset.number.toString()
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val key = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(4), dp(3), dp(3))
                isClickable = true
                isFocusable = true
                contentDescription = "Preset ${preset.number}, ${preset.name}"
                addView(image, LinearLayout.LayoutParams(MATCH_PARENT, dp(38)))
                addView(number, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    playPreset(preset.number)
                }
            }
            presetBank.addView(key, LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
            loadArt(preset.artUrl, image)
            PresetKey(preset, key, number)
        }
    }

    private fun render() {
        val currentStatus = status
        val currentMeta = meta
        val name = deviceName

        // Automatic reconnect may have moved the WiiM to a new IP.
        if (!hostInput.hasFocus() && hostInput.text.toString() != connection.host) {
            hostInput.setText(connection.host)
        }

        (statusDot.background as? GradientDrawable)?.setColor(if (reachable) PHOSPHOR else FAULT)
            ?: run { statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (reachable) PHOSPHOR else FAULT)
            } }
        statusText.text = when {
            reachable && playerBusy -> "Connected, but the WiiM player is not answering"
            reachable -> "Connected to ${name ?: "WiiM"}"
            connection.host.isEmpty() -> "No WiiM selected"
            currentStatus == null -> "Looking for WiiM at ${connection.host}"
            else -> "Lost connection to ${connection.host}"
        }

        if (reachable && playerBusy) {
            titleText.text = "WiiM player not responding"
            artistText.text = "Restart the WiiM if this stays."
            stationText.text = ""
        } else if (reachable) {
            titleText.text = currentMeta?.title?.ifEmpty { null }
                ?: currentStatus?.fallbackTitle?.ifEmpty { null } ?: "Nothing playing"
            artistText.text = currentMeta?.artist?.ifEmpty { null } ?: currentStatus?.fallbackArtist.orEmpty()
            stationText.text = currentMeta?.subtitle?.ifEmpty { null } ?: currentMeta?.album.orEmpty()
            loadArt(currentMeta?.artUrl.orEmpty(), artView)
        } else {
            titleText.text = "WiiM not connected"
            artistText.text = "Tap Find WiiM to search this network."
            stationText.text = ""
        }

        val playing = reachable && currentStatus?.isPlaying == true
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (playing) "Pause" else "Play"
        listOf(previousButton, playButton, nextButton).forEach {
            it.isEnabled = reachable
            it.alpha = if (reachable) 1f else 0.4f
        }

        if (presetKeys.map { it.preset } != presets) rebuildPresetKeys()
        val active = activePresetIndex()
        presetKeys.forEachIndexed { index, key ->
            val lit = index == active
            key.view.background = keyBackground(lit)
            key.view.translationY = if (lit) dp(2).toFloat() else 0f
            key.view.isSelected = lit
            key.view.alpha = if (reachable) 1f else 0.5f
            key.view.isEnabled = reachable
            key.number.setTextColor(if (lit) PHOSPHOR else MUTED)
        }
        presetName.text = presets.getOrNull(active)?.name.orEmpty()
        presetName.visibility = if (presetName.text.isEmpty()) View.GONE else View.VISIBLE

        if (!draggingVolume) {
            val volume = currentStatus?.volume
            volumeLabel.text = when {
                !reachable || volume == null -> "WiiM volume"
                fixedVolume == true -> "WiiM volume $volume% (fixed)"
                else -> "WiiM volume $volume%"
            }
            if (volume != null) volumeSlider.progress = volume
        }
        volumeSlider.isEnabled = reachable && fixedVolume != true
        volumeSlider.alpha = if (volumeSlider.isEnabled) 1f else 0.4f
        fixedVolumeButton.isEnabled = reachable && fixedVolume != null
        fixedVolumeButton.text = "Fixed volume: " + when (fixedVolume) { true -> "On"; false -> "Off"; null -> "?" }
        eqButton.isEnabled = reachable
        outputButton.isEnabled = reachable
        eqButton.text = "EQ: " + (eq?.let { if (it.enabled) it.name else "Off" } ?: "?")
        outputButton.text = "Output: " + (output?.label ?: "?")
    }

    // Player

    private fun refresh() {
        val host = connection.host
        try {
            val nextStatus = connection.api.status()
            val nextMeta = runCatching { connection.api.meta() }.getOrNull()
            val nextPresets = presets.ifEmpty { runCatching { connection.api.presets() }.getOrDefault(emptyList()) }
            val nextName = deviceName ?: runCatching { connection.api.device().name }.getOrNull()
            // The user may have picked another WiiM while this request was running.
            if (host != connection.host) return
            presets = nextPresets
            deviceName = nextName
            status = nextStatus
            meta = nextMeta
            reachable = true
            playerBusy = false
            val now = System.currentTimeMillis()
            if (now - soundReadAt > SOUND_REFRESH_MS) {
                soundReadAt = now
                eq = runCatching { connection.api.eq() }.getOrDefault(eq)
                output = runCatching { connection.api.output() }.getOrDefault(output)
                fixedVolume = runCatching { connection.api.stats().fixedVolume }.getOrDefault(fixedVolume)
            }
            val key = listOf(
                nextStatus.status, nextStatus.vendor, nextStatus.volume, nextStatus.muted,
                nextMeta?.subtitle, nextMeta?.sampleRate, nextMeta?.bitDepth, nextMeta?.bitRate,
                eq, output, fixedVolume,
            )
            if (key != infoKey) {
                infoKey = key
                if (showingInfo) main.post { showTechnicalInfo(quiet = true) }
            }
        } catch (_: JSONException) {
            // Device info still answering means the network is fine and only the player stalled.
            val answers = runCatching { connection.api.stats() }.isSuccess
            if (host == connection.host) {
                reachable = answers
                playerBusy = answers
            }
        } catch (_: Exception) {
            if (host == connection.host) {
                reachable = false
                playerBusy = false
            }
        }
    }

    private fun activePresetIndex(): Int = matchPresetIndex(presets, status, meta, lastPreset)

    private fun playPause() {
        val current = status
        val preset = lastPreset ?: presets.firstOrNull()?.number
        DebugLog.log("phone", if (current?.isPlaying == true) "pause" else "play")
        command {
            when {
                current?.isPlaying == true -> connection.api.pause()
                current?.isIdle == true && preset != null -> connection.api.playPreset(preset)
                else -> connection.api.resume()
            }
        }
    }

    /** Matches the steering wheel: tracks when the source has them, otherwise presets. */
    private fun step(direction: Int) {
        if (status?.isTrackBased == true && activePresetIndex() < 0) {
            DebugLog.log("phone", if (direction > 0) "next track" else "previous track")
            command { if (direction > 0) connection.api.next() else connection.api.previous() }
            return
        }
        if (presets.isEmpty()) return
        val index = activePresetIndex()
        val target = when {
            index >= 0 -> (index + direction + presets.size) % presets.size
            direction > 0 -> 0
            else -> presets.lastIndex
        }
        playPreset(presets[target].number)
    }

    private fun playPreset(number: Int) {
        lastPreset = number
        DebugLog.log("phone", "preset $number")
        command(PRESET_SETTLE_MS) { connection.api.playPreset(number) }
    }

    private fun command(settleMs: Long = COMMAND_SETTLE_MS, action: () -> Unit) {
        playerIo.execute {
            try {
                action()
                Thread.sleep(settleMs)
                refresh()
            } catch (_: InterruptedException) {
                return@execute
            } catch (_: Exception) {
                reachable = false
            }
            main.post { render() }
        }
    }

    // Connection tools

    private fun test() {
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            showOutput("Enter the WiiM IP address, or tap Find WiiM.")
            return
        }
        applyHost(host)
        runTool("Testing every network route...") {
            buildString {
                append(connection.diagnose())
                appendLine()
                try {
                    val currentStatus = connection.api.status()
                    val currentMeta = connection.api.meta()
                    val list = connection.api.presets()
                    appendLine("Status: ${currentStatus.status}, source ${currentStatus.vendor}")
                    if (currentMeta != null) {
                        appendLine("Now: ${currentMeta.artist} - ${currentMeta.title}")
                        appendLine("Station: ${currentMeta.subtitle}")
                    } else {
                        appendLine("Now: nothing loaded")
                    }
                    appendLine("Volume: ${currentStatus.volume}")
                    appendLine("Presets: ${list.size}")
                    list.forEach { appendLine("  ${it.number}. ${it.name}") }
                } catch (e: Exception) {
                    appendLine("WiiM did not answer: ${e.message}")
                }
            }.trimEnd()
        }
    }

    private fun find() {
        runTool("Searching local networks...") {
            val found = connection.scan()
            when (found.size) {
                0 -> "No WiiM found on this network.\n\n${connection.diagnose().trimEnd()}"
                1 -> {
                    val (ip, device) = found.single()
                    main.post { selectDevice(ip) }
                    "Saved ${device.name} at $ip"
                }
                else -> {
                    main.post { chooseDevice(found) }
                    "Found ${found.size} WiiM devices. Choose one to control."
                }
            }
        }
    }

    private fun playTestSound() {
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            showOutput("Enter the WiiM IP address, or tap Find WiiM.")
            return
        }
        applyHost(host)
        if (status?.isPlaying == true && activePresetIndex() < 0) {
            // Only presets can be restored afterwards.
            AlertDialog.Builder(this)
                .setTitle("Stop current playback?")
                .setMessage("The test sound replaces what the WiiM is playing, and the app can't restart this source.")
                .setPositiveButton("Play test sound") { _, _ -> startTestSound() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        startTestSound()
    }

    private fun startTestSound() {
        runTool("Starting test sound: low tone left, then higher tone right...") {
            val before = connection.api.status()
            val resumePreset = if (before.isPlaying) presets.getOrNull(activePresetIndex())?.number else null
            val phoneIp = connection.localIpFor(connection.host)
                ?: return@runTool "The phone is not on the same network as the WiiM at ${connection.host}."

            val report = TestToneServer(TestTone.wav(), phoneIp, connection.host).use { server ->
                connection.api.playUrl(server.url)
                val started = System.currentTimeMillis()
                var playing = false
                while (System.currentTimeMillis() - started < TEST_START_TIMEOUT_MS) {
                    Thread.sleep(250)
                    if (connection.api.status().status == "play") {
                        playing = true
                        break
                    }
                }
                if (!playing) {
                    "The WiiM did not start the test sound."
                } else {
                    Thread.sleep(TEST_METADATA_DELAY_MS)
                    val format = connection.api.meta()
                    Thread.sleep(TestTone.DURATION_MS - TEST_METADATA_DELAY_MS + TEST_END_MARGIN_MS)
                    "Played the test sound at ${TestTone.SAMPLE_RATE / 1000} kHz / ${TestTone.BIT_DEPTH}-bit, " +
                        "${TestTone.LEVEL_DB.toInt()} dB.\n" +
                        "You should hear a low tone on the left, then a higher tone on the right.\n" +
                        "WiiM reports: ${format?.sampleRate ?: "?"} Hz, ${format?.bitDepth ?: "?"}-bit"
                }
            }

            if (resumePreset != null) {
                lastPreset = resumePreset
                connection.api.playPreset(resumePreset)
                "$report\nReturned to preset $resumePreset."
            } else {
                report
            }
        }
    }

    private fun chooseDevice(found: List<Pair<String, DeviceInfo>>) {
        if (isFinishing || isDestroyed) return
        val labels = found.map { (ip, device) -> "${device.name}  $ip" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose your WiiM")
            .setItems(labels) { _, index ->
                val (ip, device) = found[index]
                selectDevice(ip)
                showOutput("Saved ${device.name} at $ip")
            }
            .setNegativeButton("Keep current", null)
            .show()
    }

    private fun selectDevice(ip: String) {
        hostInput.setText(ip)
        applyHost(ip)
    }

    private fun applyHost(host: String) {
        if (host != connection.host) {
            deviceName = null
            presets = emptyList()
            status = null
            meta = null
        }
        connection.host = host
    }

    private fun runTool(progress: String, work: () -> String) {
        showOutput(progress)
        val tools = listOf(testButton, findButton, testSoundButton)
        tools.forEach { it.isEnabled = false }
        toolsIo.execute {
            val result = try {
                work()
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            DebugLog.log("tool", result)
            main.post {
                if (!isDestroyed) {
                    showOutput(result)
                    tools.forEach { it.isEnabled = true }
                }
            }
        }
    }

    private fun shareDebugLog() {
        toolsIo.execute {
            val log = DebugLog.shareText()
            main.post {
                if (isDestroyed) return@post
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Car Remote for WiiM debug log")
                    .putExtra(Intent.EXTRA_TEXT, log)
                startActivity(Intent.createChooser(send, "Share debug log"))
            }
        }
    }

    private fun showOutput(value: String, technicalInfo: Boolean = false) {
        showingInfo = technicalInfo
        toolOutput.text = value
        toolOutput.visibility = View.VISIBLE
    }

    // Artwork

    private fun loadArt(url: String, target: ImageView) {
        if (target.tag == url) return
        target.tag = url
        if (!url.startsWith("https://")) {
            target.setImageDrawable(null)
            return
        }
        artCache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        artIo.execute {
            val bitmap = runCatching { fetchArt(url) }.getOrNull()
            main.post {
                if (bitmap != null) artCache.put(url, bitmap)
                if (!isDestroyed && target.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchArt(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = ART_TIMEOUT_MS
        connection.readTimeout = ART_TIMEOUT_MS
        try {
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_MAX_PX) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }

    // View helpers

    private fun transportButton(icon: Int, label: String, primary: Boolean, action: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            imageTintList = ColorStateList.valueOf(if (primary) DASH else PAPER)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            scaleType = ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (primary) PHOSPHOR else KEY)
                },
                null,
            )
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(PAPER)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(KEY, 9), null)
        setOnClickListener { action() }
    }

    private fun keyBackground(lit: Boolean): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(RIPPLE),
        rounded(if (lit) KEY_LIT else KEY, 7, if (lit) PHOSPHOR else KEY_EDGE, if (lit) 2 else 1),
        null,
    )

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    private fun text(size: Float, color: Int, condensed: Boolean = false, bold: Boolean = false) =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                if (condensed) "sans-serif-condensed" else "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }

    private fun matchWrap(top: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class PresetKey(val preset: Preset, val view: LinearLayout, val number: TextView)

    private companion object {
        const val POLL_MS = 2000L
        const val COMMAND_SETTLE_MS = 150L
        const val PRESET_SETTLE_MS = 800L
        const val SOUND_REFRESH_MS = 10_000L
        const val ART_TIMEOUT_MS = 5000
        const val ART_MAX_PX = 512
        const val TEST_START_TIMEOUT_MS = 6000L
        const val TEST_METADATA_DELAY_MS = 1000L
        const val TEST_END_MARGIN_MS = 500L

        const val DASH = 0xFF12262C.toInt()
        const val PANEL = 0xFF1B3840.toInt()
        const val GLASS_EDGE = 0xFF2E5560.toInt()
        const val KEY = 0xFF294951.toInt()
        const val KEY_EDGE = 0xFF3A5F68.toInt()
        const val KEY_LIT = 0xFF1F5A58.toInt()
        const val PHOSPHOR = 0xFF8FF0D8.toInt()
        const val PAPER = 0xFFE6EFEA.toInt()
        const val MUTED = 0xFF9DB6B4.toInt()
        const val FAULT = 0xFFFF9B7A.toInt()
        const val RIPPLE = 0x558FF0D8
    }
}
