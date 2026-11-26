package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/** Blocking platform output with one owned, independently cancellable render generation. */
internal class JvmAudioOutput(
    override val format: AudioFormat,
    private val line: SourceDataLine,
    private val bufferFrames: Int,
    private val onEvent: (AudioOutputEvent) -> Unit,
) : AudioOutput {
    // Capacity is an estimate of buffering, not current queued depth or acoustic latency.
    override val latency: Latency
        get() = Latency(line.bufferSize / (format.channels * 2), LatencyQuality.Estimated)

    // Java Sound does not say at which format the hardware runs.
    override val deviceFormat: AudioFormat? get() = null

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
            next.worker = Thread({ pump(next, callback) }, "KiteAudio-output-jvm").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 2
            }
            generation = next
            try {
                line.start()
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
            val floats = FloatArray(bufferFrames * format.channels)
            val pcm = ByteArray(floats.size * 2)
            val fade = StopFade(format.sampleRate, format.channels)
            val observer: (AudioOutputEvent) -> Unit = { event ->
                if (event is AudioOutputEvent.Failed) current.active = false
                deliverAudioEvent(onEvent, event)
            }
            if (current.active && !current.fading) deliverAudioEvent(onEvent, AudioOutputEvent.Started(format, latency))
            val lineBytes = line.bufferSize
            // The bytes this stream has written. The line starts empty, so it is checked only once it was full.
            var streamBytes = 0L
            while (current.active) {
                // A stop before the first block leaves nothing to fade.
                if (current.fading && streamBytes == 0L) break
                val filled = renderAudioBlock(callback, floats, bufferFrames, format.framesToMicros(bufferFrames), observer)
                if (filled == RENDER_FAILED) break
                if (!current.active) break
                // A stop by the app, also one from inside this render, fades this block and the next ones out.
                val fading = current.fading
                val fadeEnded = fading && fade.apply(floats, bufferFrames)
                var offset = 0
                for (sample in floats) {
                    val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[offset++] = (value and 0xff).toByte()
                    pcm[offset++] = ((value shr 8) and 0xff).toByte()
                }
                // Java Sound has no underrun counter. A line with no bytes left to play has run dry.
                // A short render already sent Underrun, so its block counts no device underrun.
                // The fade and its play-out count nothing.
                val ranDry = !fading && streamBytes >= lineBytes && filled == bufferFrames && line.available() >= lineBytes
                streamBytes += write(current, pcm)
                if (ranDry && current.active) deviceUnderruns.report(1, onEvent)
                if (fadeEnded) {
                    playOut(current, pcm)
                    break
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

    /** Writes all of [pcm] and returns the bytes written. A stream that ends stops the write early. */
    private fun write(current: Generation, pcm: ByteArray): Int {
        var written = 0
        var noProgress = 0
        while (current.active && written < pcm.size) {
            val count = line.write(pcm, written, pcm.size - written)
            if (!current.active) break
            if (count < 0 || count > pcm.size - written || count % (format.channels * 2) != 0) {
                lose(current, "SourceDataLine.write returned invalid count $count")
                break
            }
            if (count == 0) {
                if (++noProgress >= 10) lose(current, "SourceDataLine.write made no progress") else Thread.sleep(1)
            } else {
                written += count
                noProgress = 0
            }
        }
        return written
    }

    /** Ends the stream for a write that failed. A stream in its fade sends no DeviceLost, because the app stopped it. */
    private fun lose(current: Generation, reason: String) {
        current.active = false
        if (!current.fading) deliverAudioEvent(onEvent, AudioOutputEvent.DeviceLost(reason))
    }

    /**
     * Writes silence for the whole line after the last block of the fade. A write returns only
     * once the line has room, so the faded block has played when the last one returns.
     */
    private fun playOut(current: Generation, pcm: ByteArray) {
        pcm.fill(0)
        var frames = line.bufferSize / (format.channels * 2)
        while (frames > 0 && current.active) {
            write(current, pcm)
            frames -= bufferFrames
        }
    }

    private fun haltOutput() {
        runCatching { line.stop() }
        runCatching { line.flush() }
    }

    override fun stop() {
        val current = synchronized(stateLock) { generation?.also { it.fading = true } } ?: return
        // A call from the worker returns at once, and the worker goes on with the fade.
        if (Thread.currentThread() === current.worker) return
        try { current.worker.join(500) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // A line that stopped taking sound, or a renderer that blocks, halts at once and unblocks the write.
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
        synchronized(stateLock) { runCatching { line.close() } }
    }
}

/** One Java Sound mixer that offers a `SourceDataLine`. Tests make their own. */
internal class JvmMixer(
    val name: String,
    /** The formats of the mixer's output lines, as Java Sound lists them. */
    val formats: List<JavaAudioFormat>,
    /** True when the mixer can open more than one line at a time. */
    val mixing: Boolean,
    /** Gets a line of the mixer, as `Mixer.getLine` does. */
    val line: (DataLine.Info) -> SourceDataLine,
)

/** The Java Sound calls that the output makes to find and open a line. Tests use a fake. */
internal interface JvmSound {
    /** Every mixer that offers a `SourceDataLine`, in the order of `AudioSystem.getMixerInfo`. */
    fun mixers(): List<JvmMixer>

    /** Gets a line of the default mixer, as `AudioSystem.getLine` does. */
    fun defaultLine(info: DataLine.Info): SourceDataLine
}

internal object PlatformJvmSound : JvmSound {
    override fun mixers(): List<JvmMixer> = AudioSystem.getMixerInfo().mapNotNull { info ->
        val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: return@mapNotNull null
        val lines = runCatching { mixer.getSourceLineInfo(Line.Info(SourceDataLine::class.java)).toList() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return@mapNotNull null
        JvmMixer(
            name = info.name,
            formats = lines.filterIsInstance<DataLine.Info>().flatMap { it.formats.toList() },
            mixing = lines.any { line -> mixer.getMaxLines(line).let { it == AudioSystem.NOT_SPECIFIED || it > 1 } },
            line = { wanted -> mixer.getLine(wanted) as SourceDataLine },
        )
    }

    override fun defaultLine(info: DataLine.Info): SourceDataLine = AudioSystem.getLine(info) as SourceDataLine
}

/** True for the signed 16-bit PCM that the output writes. Java Sound may leave the size unspecified. */
private fun JavaAudioFormat.isSigned16(): Boolean =
    encoding == JavaAudioFormat.Encoding.PCM_SIGNED &&
        (sampleSizeInBits == 16 || sampleSizeInBits == AudioSystem.NOT_SPECIFIED)

/**
 * The devices for [mixers], each with its mixer.
 *
 * The id is the mixer name, with " #2", " #3" and so on added when names repeat, so it is unique
 * in the list. The default device is the first mixer that can mix, else the first mixer, which is
 * the mixer that `AudioSystem.getLine` picks when no system property names one. The rates and
 * channel counts come from the signed 16-bit formats where Java Sound gives a number.
 */
internal fun jvmOutputDevices(mixers: List<JvmMixer>): List<Pair<AudioOutputDevice, JvmMixer>> {
    val defaultIndex = mixers.indexOfFirst { it.mixing }.let { if (it < 0) 0 else it }
    val used = HashSet<String>()
    return mixers.mapIndexed { index, mixer ->
        var id = mixer.name
        var copy = 1
        while (!used.add(id)) id = "${mixer.name} #${++copy}"
        val pcm = mixer.formats.filter { it.isSigned16() }
        val device = AudioOutputDevice(
            id = id,
            name = mixer.name,
            isDefault = index == defaultIndex,
            sampleRates = pcm.map { it.sampleRate }.filter { it > 0f }.map { it.toInt() }.distinct().sorted(),
            channelCounts = pcm.map { it.channels }.filter { it > 0 }.distinct().sorted(),
        )
        device to mixer
    }
}

/** The JVM body of [openAudioOutput], over [sound]. */
@Throws(Exception::class)
internal fun openJvmOutput(
    format: AudioFormat,
    bufferFrames: Int,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    onEvent: (AudioOutputEvent) -> Unit,
    sound: JvmSound,
): AudioOutput {
    requireSupportedLayout(format)
    validateBufferFrames(format, bufferFrames)
    // Only a chosen device or a Nearest open needs the list.
    val devices by lazy { jvmOutputDevices(sound.mixers()) }
    val chosen = if (device == null) null else {
        devices.firstOrNull { it.first.id == device.id } ?: run {
            val reason = unknownDeviceReason(device)
            val problem = LineUnavailableException(reason)
            deliverAudioEvent(onEvent, AudioOutputEvent.Failed(reason, problem))
            throw problem
        }
    }
    val mixer = chosen?.second
    // With no device, the rates of the default device are the best guess at the line's own.
    val rates = when {
        chosen != null -> chosen.first.sampleRates
        formatPolicy == AudioFormatPolicy.Nearest -> devices.firstOrNull { it.first.isDefault }?.first?.sampleRates.orEmpty()
        else -> emptyList()
    }
    var firstFailure: Exception? = null
    val opened = openFirstFormat(format, formatPolicy, rates) { candidate ->
        val javaFormat = JavaAudioFormat(
            candidate.sampleRate.toFloat(),
            16,
            candidate.channels,
            true,          // signed
            false,         // little-endian, which is what every desktop wants
        )
        val info = DataLine.Info(SourceDataLine::class.java, javaFormat)
        try {
            val line = if (mixer == null) sound.defaultLine(info) else mixer.line(info)
            // Ask for four blocks' worth. One block is a click waiting to happen; more than four
            // is latency nobody asked for.
            line.open(javaFormat, bufferFrames * candidate.channels * 2 * 4)
            candidate to line
        } catch (problem: LineUnavailableException) {
            if (firstFailure == null) firstFailure = problem
            null
        } catch (problem: IllegalArgumentException) {
            // Java Sound throws this for a format that no line supports.
            if (firstFailure == null) firstFailure = problem
            null
        }
    }
    if (opened == null) {
        val problem = firstFailure!!
        if (problem is LineUnavailableException) {
            deliverAudioEvent(onEvent, AudioOutputEvent.Failed("no audio output is available", problem))
        }
        throw problem
    }
    return JvmAudioOutput(opened.first, opened.second, bufferFrames, onEvent)
}

@Throws(Exception::class)
public actual fun openAudioOutput(
    format: AudioFormat,
    bufferFrames: Int,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput = openJvmOutput(format, bufferFrames, device, formatPolicy, onEvent, PlatformJvmSound)

/** Every Java Sound mixer that offers an output line. */
public actual suspend fun audioOutputDevices(): List<AudioOutputDevice> = jvmOutputDevices(PlatformJvmSound.mixers()).map { it.first }

public actual fun audioOutputAvailable(): Boolean = runCatching {
    PlatformJvmSound.mixers().any { mixer -> mixer.formats.any { it.isSigned16() } }
}.getOrDefault(false)
