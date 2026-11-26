package io.github.yuroyami.kiteaudio.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import io.github.yuroyami.kiteaudio.core.AudioFormat

/** The calls that [AndroidAudioOutput] makes on the platform. Tests drive the output with a fake. */
internal interface AndroidPcmTrack {
    val bufferSizeInFrames: Int
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun write(data: ShortArray, offset: Int, count: Int): Int

    /** The underruns that the system counted since the track was created, or -1 below API level 24. */
    val underrunCount: Int

    /** Runs once on the worker thread before the first block. */
    fun raiseWorkerPriority()

    /** The format that the hardware runs at, or null when Android does not say. */
    val deviceFormat: AudioFormat? get() = null
}

private class PlatformPcmTrack(private val track: AudioTrack) : AndroidPcmTrack {
    private val countsUnderruns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    override val bufferSizeInFrames: Int get() = track.bufferSizeInFrames
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
    override fun write(data: ShortArray, offset: Int, count: Int): Int = track.write(data, offset, count)
    override val underrunCount: Int get() = if (countsUnderruns) track.underrunCount else -1
    override fun raiseWorkerPriority() = Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

    // The rate of the system's output for music. The channel count only when the routed device names one.
    override val deviceFormat: AudioFormat?
        get() {
            val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            val channels = track.routedDevice?.channelCounts?.singleOrNull() ?: return null
            return if (rate > 0 && channels > 0) AudioFormat(rate, channels) else null
        }
}

/** Blocking platform output with one owned, independently cancellable render generation. */
internal class AndroidAudioOutput(
    override val format: AudioFormat,
    private val track: AndroidPcmTrack,
    private val bufferFrames: Int,
    private val onEvent: (AudioOutputEvent) -> Unit,
) : AudioOutput {
    // Capacity is an estimate of buffering, not current queued depth or acoustic latency.
    override val latency: Latency
        get() = Latency(track.bufferSizeInFrames, LatencyQuality.Estimated)

    override val deviceFormat: AudioFormat? get() = track.deviceFormat

    private class Generation {
        @Volatile var active: Boolean = false
        /** True once the app stopped the stream and its sound fades out. */
        @Volatile var fading: Boolean = false
        lateinit var worker: Thread
    }

    private val stateLock = Any()
    @Volatile private var generation: Generation? = null
    // Only the worker thread uses it, and one worker ends before the next one starts.
    private val deviceUnderruns = DeviceUnderrunCounter()
    private var closed = false
    override val isRunning: Boolean get() = generation?.let { it.active && !it.fading } == true

    override fun start(callback: AudioRenderCallback) {
        var failure: Throwable? = null
        var failedGeneration: Generation? = null
        synchronized(stateLock) {
            check(!closed) { "the audio output is closed" }
            val previous = generation
            if (previous != null && previous.active && !previous.fading) return
            check(previous == null) { "the previous audio callback is still stopping" }
            val next = Generation()
            next.worker = Thread({ pump(next, callback) }, "KiteAudio-output-android").apply {
                isDaemon = true

            }
            generation = next
            try {
                track.play()
                next.active = true
                next.worker.start()
            } catch (problem: Throwable) {
                next.active = false
                haltOutput()
                failedGeneration = next
                failure = problem
            }
        }
        failure?.let {
            try { deliverAudioEvent(onEvent, AudioOutputEvent.Failed("the audio output would not start", it)) }
            finally { synchronized(stateLock) { if (generation === failedGeneration) generation = null } }
        }
    }

    private fun pump(current: Generation, callback: AudioRenderCallback) {
        try {
            track.raiseWorkerPriority()
            val floats = FloatArray(bufferFrames * format.channels)
            val pcm = ShortArray(floats.size)
            val fade = StopFade(format.sampleRate, format.channels)
            val observer: (AudioOutputEvent) -> Unit = { event ->
                if (event is AudioOutputEvent.Failed) current.active = false
                deliverAudioEvent(onEvent, event)
            }
            if (current.active && !current.fading) deliverAudioEvent(onEvent, AudioOutputEvent.Started(format, latency))
            // The system counter after the last written block. The first block of a stream only sets it.
            var underrunsSeen = -1
            var rendered = false
            while (current.active) {
                // A stop before the first block leaves nothing to fade.
                if (current.fading && !rendered) break
                val filled = renderAudioBlock(callback, floats, bufferFrames, format.framesToMicros(bufferFrames), observer)
                rendered = true
                if (filled == RENDER_FAILED) break
                if (!current.active) break
                // A stop by the app, also one from inside this render, fades this block and the next ones out.
                val fading = current.fading
                val fadeEnded = fading && fade.apply(floats, bufferFrames)
                for (i in floats.indices) {
                    pcm[i] = (floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                write(current, pcm)
                if (!current.active) break
                if (fadeEnded) {
                    playOut(current, pcm)
                    break
                }
                // The fade and its play-out count nothing.
                if (fading) continue
                val underruns = track.underrunCount
                if (underruns >= 0) {
                    // A short render already sent Underrun, so its block counts no device underrun.
                    if (underrunsSeen >= 0 && filled == bufferFrames) deviceUnderruns.report(underruns - underrunsSeen, onEvent)
                    underrunsSeen = underruns
                }
            }
        } catch (problem: Throwable) {
            current.active = false
            deliverAudioEvent(onEvent, AudioOutputEvent.Failed("the audio worker failed", problem))
        } finally {
            current.active = false
            synchronized(stateLock) { haltOutput() }
            try {
                deliverAudioEvent(onEvent, AudioOutputEvent.Stopped)
            } finally {
                synchronized(stateLock) { if (generation === current) generation = null }
            }
        }
    }

    /** Writes all of [pcm]. A stream that ends stops the write early. */
    private fun write(current: Generation, pcm: ShortArray) {
        var written = 0
        var noProgress = 0
        while (current.active && written < pcm.size) {
            val count = track.write(pcm, written, pcm.size - written)
            if (!current.active) break
            if (count < 0 || count > pcm.size - written || count % (format.channels) != 0) {
                lose(current, "AudioTrack.write returned invalid count $count")
                break
            }
            if (count == 0) {
                if (++noProgress >= 10) lose(current, "AudioTrack.write made no progress") else Thread.sleep(1)
            } else {
                written += count
                noProgress = 0
            }
        }
    }

    /** Ends the stream for a write that failed. A stream in its fade sends no DeviceLost, because the app stopped it. */
    private fun lose(current: Generation, reason: String) {
        current.active = false
        if (!current.fading) deliverAudioEvent(onEvent, AudioOutputEvent.DeviceLost(reason))
    }

    /**
     * Writes silence for the whole track buffer after the last block of the fade. A blocking
     * write returns only once the track has room, so the faded block has played when the last one returns.
     */
    private fun playOut(current: Generation, pcm: ShortArray) {
        pcm.fill(0)
        var frames = track.bufferSizeInFrames
        while (frames > 0 && current.active) {
            write(current, pcm)
            frames -= bufferFrames
        }
    }

    private fun haltOutput() {
        runCatching { track.pause() }
        runCatching { track.flush() }
    }

    override fun stop() {
        val current = synchronized(stateLock) { generation?.also { it.fading = true } } ?: return
        // A call from the worker returns at once, and the worker goes on with the fade.
        if (Thread.currentThread() === current.worker) return
        try { current.worker.join(500) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // A track that stopped taking sound, or a renderer that blocks, halts at once and unblocks the write.
        if (current.worker.isAlive) synchronized(stateLock) {
            if (generation === current) {
                current.active = false
                haltOutput()
            }
        }
        // The worker retains ownership until finally, even after the bounded wait expires.
    }

    override fun close() {
        val current = synchronized(stateLock) {
            closed = true
            generation?.also {
                it.active = false
                // Unblock a platform write before waiting for the worker.
                haltOutput()
            }
        }
        if (current != null && Thread.currentThread() !== current.worker) {
            try { current.worker.join(500) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(stateLock) { runCatching { track.release() } }
    }
}

/**
 * A failed track build. [reason] goes into [AudioOutputEvent.Failed]. A failure with [tryOthers]
 * false is about the device, not the format, so [AudioFormatPolicy.Nearest] tries no other format.
 */
internal class AndroidOpenFailure(val reason: String, message: String, val tryOthers: Boolean = true) :
    UnsupportedOperationException(message)

/** Builds the platform track. Tests use a fake. */
internal fun interface AndroidTrackFactory {
    /**
     * A track for [format] that plays on [route], or on the system route when [route] is null.
     * [route] is an object that [rememberAndroidDevices] stored.
     *
     * @throws AndroidOpenFailure when the platform rejects the format or the route
     */
    fun create(format: AudioFormat, bufferFrames: Int, route: Any?): AndroidPcmTrack
}

/** The id of the entry that stands for the system route. An open with it plays where Android chooses. */
internal const val ANDROID_SYSTEM_ROUTE_ID: String = "system-route"

internal val androidSystemRoute: AudioOutputDevice =
    AudioOutputDevice(ANDROID_SYSTEM_ROUTE_ID, "System route", isDefault = true, sampleRates = emptyList(), channelCounts = emptyList())

/** The devices of the last full list, by id, each with its platform object for `setPreferredDevice`. */
private val androidRoutesLock = Any()
private var androidRoutes: Map<String, Pair<AudioOutputDevice, Any>> = emptyMap()

/** Keeps [entries] as the devices that an open can choose, and returns the list with the system route first. */
internal fun rememberAndroidDevices(entries: List<Pair<AudioOutputDevice, Any>>): List<AudioOutputDevice> {
    synchronized(androidRoutesLock) { androidRoutes = entries.associateBy { it.first.id } }
    return listOf(androidSystemRoute) + entries.map { it.first }
}

/** The device and platform object stored for [id], or null when the last full list did not hold it. */
internal fun androidRoute(id: String): Pair<AudioOutputDevice, Any>? = synchronized(androidRoutesLock) { androidRoutes[id] }

/** A device entry from the fields of an `AudioDeviceInfo`. */
internal fun androidOutputDevice(id: Int, type: Int, productName: String, sampleRates: IntArray, channelCounts: IntArray): AudioOutputDevice {
    val kind = androidDeviceKind(type)
    val name = if (productName.isBlank() || productName == kind) kind else "$kind ($productName)"
    return AudioOutputDevice(
        id = id.toString(),
        name = name,
        isDefault = false,
        sampleRates = sampleRates.filter { it > 0 }.distinct().sorted(),
        channelCounts = channelCounts.filter { it > 0 }.distinct().sorted(),
    )
}

private fun androidDeviceKind(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Built-in speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
    AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID -> "Hearing aid"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out"
    AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "Dock"
    AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
    else -> "Audio output"
}

/** The Android body of [openAudioOutput], over [factory]. */
@Throws(Exception::class)
internal fun openAndroidOutput(
    format: AudioFormat,
    bufferFrames: Int,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    onEvent: (AudioOutputEvent) -> Unit,
    factory: AndroidTrackFactory,
): AudioOutput {
    requireSupportedLayout(format)
    validateBufferFrames(format, bufferFrames)
    val chosen = if (device == null || device.id == ANDROID_SYSTEM_ROUTE_ID) null else {
        androidRoute(device.id) ?: run {
            val reason = "no audio output device has the id \"${device.id}\" (${device.name}); " +
                "list the devices with audioOutputDevices(context) first"
            deliverAudioEvent(onEvent, AudioOutputEvent.Failed(reason))
            throw UnsupportedOperationException(reason)
        }
    }
    var firstFailure: AndroidOpenFailure? = null
    var stop = false
    val opened = openFirstFormat(format, formatPolicy, chosen?.first?.sampleRates.orEmpty()) { candidate ->
        if (stop) return@openFirstFormat null
        try {
            candidate to factory.create(candidate, bufferFrames, chosen?.second)
        } catch (problem: AndroidOpenFailure) {
            if (firstFailure == null) firstFailure = problem
            if (!problem.tryOthers) stop = true
            null
        }
    }
    if (opened == null) {
        val problem = firstFailure!!
        deliverAudioEvent(onEvent, AudioOutputEvent.Failed(problem.reason))
        throw problem
    }
    return AndroidAudioOutput(opened.first, opened.second, bufferFrames, onEvent)
}

private object PlatformTrackFactory : AndroidTrackFactory {
    override fun create(format: AudioFormat, bufferFrames: Int, route: Any?): AndroidPcmTrack {
        val channelMask = if (format.channels == 1) {
            AndroidAudioFormat.CHANNEL_OUT_MONO
        } else {
            AndroidAudioFormat.CHANNEL_OUT_STEREO
        }

        val minimum = AudioTrack.getMinBufferSize(
            format.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            throw AndroidOpenFailure("AudioTrack reports no usable buffer size ($minimum)", "AudioTrack reports no usable buffer size")
        }

        // Whichever is larger: what the device insists on, or four of our blocks. Asking for less
        // than the minimum fails outright, and asking for one block is a click waiting to happen.
        val bytes = maxOf(minimum, bufferFrames * format.channels * 2 * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .apply {
                // The low-latency flag exists from Oreo. On older devices the default path is what
                // there is.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw AndroidOpenFailure("AudioTrack would not initialise", "AudioTrack would not initialise")
        }
        if (route != null && !track.setPreferredDevice(route as AudioDeviceInfo)) {
            track.release()
            val reason = "AudioTrack would not play on the audio output device ${route.id} (${route.productName})"
            throw AndroidOpenFailure(reason, reason, tryOthers = false)
        }
        return PlatformPcmTrack(track)
    }
}

@Throws(Exception::class)
public actual fun openAudioOutput(
    format: AudioFormat,
    bufferFrames: Int,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput = openAndroidOutput(format, bufferFrames, device, formatPolicy, onEvent, PlatformTrackFactory)

/**
 * One entry, with the id `"system-route"`, that stands for the route that Android chooses. Its
 * rates and channel counts are empty. Opening it is the same as an open with no device. The
 * list of real devices needs a `Context`: call `audioOutputDevices(context)`.
 */
public actual suspend fun audioOutputDevices(): List<AudioOutputDevice> = listOf(androidSystemRoute)

/**
 * Every output device that `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` reports, after the
 * system route entry of the common [audioOutputDevices].
 *
 * The system route entry is the default. The other entries have the id of their `AudioDeviceInfo`,
 * and [openAudioOutput] plays on one through `AudioTrack.setPreferredDevice`. An open can choose
 * only a device of the last list that this function returned, so call it again after a device
 * connects. Android does not say which device the system route uses now.
 */
public fun audioOutputDevices(context: Context): List<AudioOutputDevice> {
    val manager = context.getSystemService(AudioManager::class.java) ?: return rememberAndroidDevices(emptyList())
    val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    return rememberAndroidDevices(
        outputs.map { info ->
            androidOutputDevice(info.id, info.type, info.productName?.toString().orEmpty(), info.sampleRates, info.channelCounts) to info
        },
    )
}

/**
 * True on every Android device.
 *
 * Android always has an audio output, even when it is only the earpiece. Whether this particular
 * code drives it correctly is a different question, and one nobody has answered yet.
 */
public actual fun audioOutputAvailable(): Boolean = true
