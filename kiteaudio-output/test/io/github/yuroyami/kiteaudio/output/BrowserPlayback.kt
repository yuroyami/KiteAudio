package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a quiet 440 Hz tone through an output from [open] for [seconds], then reports what the
 * output said: the latency it started with, how many blocks it rendered, and every underrun, device
 * underrun, overrun and failure. The tone stops itself from inside the renderer, which is the path a stop from the
 * renderer takes. Not a test: the web test modules export it for kiteaudio-output/verification/web,
 * which runs it in a browser. Run from a click it plays at once. Run before one, it waits, and the
 * function it returns starts the output again, which is what a click handler would do. An output
 * that lost its device stays open after its report, so that function can start it once more and
 * show what a start after the loss does. Otherwise that function does nothing once the check has
 * finished, and says so through [report].
 */
internal fun browserPlaybackCheck(
    seconds: Int,
    open: (AudioFormat, Int, (AudioOutputEvent) -> Unit) -> AudioOutput,
    report: (String) -> Unit,
): () -> Unit {
    val format = AudioFormat(48_000, ChannelLayout.STEREO)
    val target = seconds.toLong() * format.sampleRate
    var played = 0L
    var blocks = 0
    var underrunFrames = 0L
    var underruns = 0
    var deviceUnderruns = 0L
    var overruns = 0
    var worstOverrunMicros = 0L
    val notes = mutableListOf<String>()
    var finished = false
    var lost = false
    var startedAfterLoss = false
    lateinit var output: AudioOutput
    output = open(format, 512) { event ->
        when (event) {
            is AudioOutputEvent.Started -> notes += "started: ${event.latency.frames} frames of latency, ${event.latency.quality}"
            is AudioOutputEvent.Underrun -> { underruns++; underrunFrames += event.frames }
            is AudioOutputEvent.DeviceUnderrun -> deviceUnderruns = event.total
            is AudioOutputEvent.Overrun -> { overruns++; if (event.tookMicros > worstOverrunMicros) worstOverrunMicros = event.tookMicros }
            is AudioOutputEvent.Failed -> notes += "failed: ${event.reason} ${event.cause ?: ""}"
            is AudioOutputEvent.DeviceLost -> { lost = true; notes += "device lost: ${event.reason}" }
            AudioOutputEvent.Stopped -> {
                val keepOpen = lost && !startedAfterLoss
                if (keepOpen) {
                    notes += "the output stays open: start it again to see what a start after the loss does"
                } else {
                    finished = true
                    output.close()
                }
                report(
                    (notes + listOf(
                        "rendered $blocks blocks, ${played * 1000 / format.sampleRate} ms of audio",
                        "underruns: $underruns, $underrunFrames frames of silence",
                        "device underruns: $deviceUnderruns",
                        "overruns: $overruns, worst $worstOverrunMicros us",
                        "stopped",
                    )).joinToString("\n"),
                )
                notes.clear()
            }
        }
    }
    val tone = AudioRenderCallback { out, frames ->
        for (i in 0 until frames) {
            val value = (0.1 * sin(2 * PI * 440.0 * (played + i) / format.sampleRate)).toFloat()
            out[i * 2] = value
            out[i * 2 + 1] = value
        }
        played += frames
        blocks++
        if (played >= target) output.stop()
        frames
    }
    output.start(tone)
    return {
        when {
            finished -> report("already finished: the output is closed")
            else -> {
                if (lost) startedAfterLoss = true
                output.start(tone)
            }
        }
    }
}

/**
 * Lists the outputs through [devices] and reports them to [report] as a JSON array of objects with
 * `id`, `name` and `isDefault`, or as a JSON object with `error` when the listing fails. Not a
 * test: the web test modules export it for kiteaudio-output/verification/web.
 */
internal fun <H : Any> browserDeviceList(devices: BrowserOutputDevices<H>, report: (String) -> Unit) {
    suspend { devices.list() }.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            report(
                result.fold(
                    { list -> list.joinToString(",", "[", "]") { "{\"id\":${jsonString(it.id)},\"name\":${jsonString(it.name)},\"isDefault\":${it.isDefault}}" } },
                    { problem -> "{\"error\":${jsonString(problem.toString())}}" },
                ),
            )
        },
    )
}

/**
 * The device with [id] in the last listing of [devices], or null for an empty [id]. An id that the
 * listing did not hold comes back as a device of its own, so the output can refuse it.
 */
internal fun <H : Any> browserChosenDevice(devices: BrowserOutputDevices<H>, id: String): AudioOutputDevice? =
    if (id.isEmpty()) null else devices.find(id) ?: AudioOutputDevice(id, "", false, emptyList(), emptyList())

private fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}
