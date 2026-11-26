package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

/**
 * The Web Audio calls the browser output makes, each a line or two on Kotlin/JS and Kotlin/Wasm.
 *
 * The output itself is ordinary common code over this interface, so both web platforms share one
 * implementation and its tests run on every platform with a stand-in. [H] is whatever the
 * platform uses to hold a JavaScript object. Every callback runs on the page's main thread.
 */
internal interface WebAudioApi<H : Any> {
    /** True when this host has `AudioContext`. */
    fun hasAudioContext(): Boolean

    /** True when this host also has `AudioWorkletNode`, which needs a secure context. */
    fun hasWorklet(): Boolean

    /** A new `AudioContext` at [sampleRate], asking for interactive latency. Throws when the host refuses. */
    fun newContext(sampleRate: Int): H

    /** The context's own `baseLatency`, in seconds, or zero when the browser does not say. */
    fun baseLatencySeconds(context: H): Double

    /** The context's `currentTime`, in seconds. */
    fun currentTime(context: H): Double

    /** Loads [source] as a worklet module. Calls exactly one of [done] and [failed], later. */
    fun loadProcessor(context: H, source: String, done: () -> Unit, failed: (String) -> Unit)

    /** Calls [changed] with the context's new `state` whenever it changes. */
    fun onStateChange(context: H, changed: (String) -> Unit)

    /** Resumes the context. [done] may wait for the page to be allowed to play sound. */
    fun resume(context: H, done: () -> Unit, failed: (String) -> Unit)

    fun suspend(context: H)

    fun close(context: H)

    /** A new worklet node running the processor called [name], connected to the speakers. Throws on failure. */
    fun newNode(context: H, name: String, channels: Int): H

    /**
     * Hears the node. [returned] gets back a block the processor has played, with its slot and
     * the frames of silence played since the last one came back. [failed] is the processor failing.
     */
    fun listen(node: H, returned: (slot: Int, underrunFrames: Int, block: H) -> Unit, failed: (String) -> Unit)

    /** Tells the processor to stop, stops listening and disconnects the node. */
    fun release(node: H)

    /** A new gain node connected to the speakers, for scheduled buffers to play through. */
    fun newGain(context: H): H

    /** Disconnects [node], silencing everything scheduled through it. */
    fun disconnect(node: H)

    /**
     * Copies [frames] interleaved frames of [block] into a new `AudioBuffer` and plays it through
     * [destination], starting at context time [at].
     */
    fun playBuffer(context: H, destination: H, channels: Int, frames: Int, block: H, at: Double)

    /** Calls [tick] every [intervalMs] milliseconds until [stopTimer]. */
    fun startTimer(intervalMs: Int, tick: () -> Unit): H

    fun stopTimer(timer: H)

    /** A new `Float32Array` of [samples] values. */
    fun newBlock(samples: Int): H

    /** Copies [samples] into [block], which is the same length. */
    fun fill(block: H, samples: FloatArray)

    /** Hands [block] to the processor, transferring its buffer rather than copying it. */
    fun post(node: H, slot: Int, block: H)

    /** True when this host's `AudioContext` takes a `sinkId`, which is Chromium 110 and newer today. */
    fun canChooseSink(): Boolean = false

    /** A new `AudioContext` at [sampleRate] that plays on the output with the id [sinkId]. Throws when the host refuses. */
    fun newContextOn(sampleRate: Int, sinkId: String): H = throw UnsupportedOperationException("this host cannot choose an audio output")

    /** Calls [failed] when the context fires an `error` event, which reports an output that does not work. */
    fun onError(context: H, failed: (String) -> Unit) {}

    /**
     * Lists the `audiooutput` entries of `navigator.mediaDevices.enumerateDevices()`, each as its
     * `deviceId` and `label`. Calls exactly one of [done] and [failed]. A host with no
     * `navigator.mediaDevices`, such as Node.js, lists nothing.
     */
    fun listOutputs(done: (List<Pair<String, String>>) -> Unit, failed: (String) -> Unit) { done(emptyList()) }
}

/**
 * The browser's outputs, and the ones that the last listing returned. An open checks a chosen
 * device against that listing, because a page cannot list the outputs without waiting.
 */
internal class BrowserOutputDevices<H : Any>(private val api: WebAudioApi<H>) {
    private var known: Map<String, AudioOutputDevice> = emptyMap()

    suspend fun list(): List<AudioOutputDevice> = suspendCoroutine { continuation ->
        api.listOutputs(
            done = { outputs ->
                val devices = browserOutputDevices(outputs)
                known = devices.associateBy { it.id }
                continuation.resume(devices)
            },
            failed = { reason -> continuation.resumeWithException(IllegalStateException("the browser would not list its audio outputs: $reason")) },
        )
    }

    /** The device with [id] in the last listing, or null. */
    fun find(id: String): AudioOutputDevice? = known[id]
}

/**
 * Devices for the `deviceId` and `label` pairs of `enumerateDevices()`. The Media Capture
 * specification puts the default output first among the outputs. Browsers give no rates.
 */
internal fun browserOutputDevices(outputs: List<Pair<String, String>>): List<AudioOutputDevice> =
    outputs.distinctBy { it.first }.mapIndexed { index, (id, label) ->
        AudioOutputDevice(id, label, isDefault = index == 0, sampleRates = emptyList(), channelCounts = emptyList())
    }

/**
 * How a browser output gets its blocks to the speakers. Either way rendering runs on the page's main
 * thread, four blocks ahead. `openAudioOutput` always asks for [Auto]; the others are for tests and
 * the browser check.
 */
internal enum class BrowserTechnique {
    /** [Worklet] where the page can run one, otherwise [Buffers], and [Buffers] if the worklet will not load. */
    Auto,

    /**
     * An `AudioWorkletNode` that hands each played block back, which asks for the next render, so
     * the device's clock paces rendering and every frame of silence is counted exactly. Needs a
     * secure context and a content security policy that allows a `blob:` worklet module.
     */
    Worklet,

    /**
     * A page timer renders ahead and books each block as an `AudioBuffer` at an exact context
     * time. Works in any page that has Web Audio, but allocates a buffer and a source node for
     * every block, and device underruns are worked out from the context clock. Browsers slow timers in a
     * hidden tab, which should play as gaps; that has not been checked yet.
     */
    Buffers,
}

/** How many blocks are in flight at once, either way: four, as on the JVM. */
internal const val WEB_AUDIO_BLOCKS: Int = 4

internal const val WEB_AUDIO_PROCESSOR: String = "kiteaudio-output"

/**
 * The audio worklet processor, loaded into the context from this text.
 *
 * It holds the blocks the page has rendered and plays them, 128 frames at a time, splitting each
 * interleaved block into the planar channels Web Audio wants. Each block it finishes goes back to
 * the page with its buffer transferred, and that return is the request for the next one, so the
 * device's own clock decides when rendering happens. It counts silence only once the first block
 * has arrived, so the wait for the first render is not an underrun.
 */
internal const val WEB_AUDIO_WORKLET: String = """
class KiteAudioOutput extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.channels = options.processorOptions.channels;
    this.queue = [];
    this.current = null;
    this.slot = -1;
    this.read = 0;
    this.underrun = 0;
    this.primed = false;
    this.stopped = false;
    this.port.onmessage = (event) => {
      const message = event.data;
      if (message.stop) { this.stopped = true; return; }
      this.queue.push(message);
      this.primed = true;
    };
  }

  process(inputs, outputs) {
    if (this.stopped) return false;
    const out = outputs[0];
    const frames = out[0].length;
    const channels = this.channels;
    let done = 0;
    while (done < frames) {
      if (this.current === null) {
        const next = this.queue.shift();
        if (next === undefined) break;
        this.current = next.data;
        this.slot = next.slot;
        this.read = 0;
      }
      const data = this.current;
      const count = Math.min(data.length / channels - this.read, frames - done);
      for (let c = 0; c < channels; c++) {
        const plane = out[c];
        let i = this.read * channels + c;
        for (let k = 0; k < count; k++, i += channels) plane[done + k] = data[i];
      }
      done += count;
      this.read += count;
      if (this.read * channels >= data.length) {
        this.port.postMessage({ slot: this.slot, data: data, underrun: this.underrun }, [data.buffer]);
        this.underrun = 0;
        this.current = null;
      }
    }
    if (done < frames) {
      for (let c = 0; c < out.length; c++) out[c].fill(0, done);
      if (this.primed) this.underrun += frames - done;
    }
    return true;
  }
}
registerProcessor("kiteaudio-output", KiteAudioOutput);
"""

/**
 * Opens a browser output over [api] on the default output with [AudioFormatPolicy.Exact]: the
 * body of `openAudioOutput` on both web platforms, which asks for [BrowserTechnique.Auto].
 */
@Throws(UnsupportedOperationException::class, IllegalArgumentException::class)
internal fun <H : Any> openBrowserOutput(
    api: WebAudioApi<H>,
    format: AudioFormat,
    bufferFrames: Int,
    technique: BrowserTechnique,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput = openBrowserOutput(api, format, bufferFrames, technique, null, AudioFormatPolicy.Exact, null, onEvent)

/**
 * Opens a browser output over [api].
 *
 * A [device] must be in the last listing of [devices]. The default entry opens a context with no
 * `sinkId`, so it works in every browser. Any other entry needs a host whose `AudioContext` takes a
 * `sinkId`.
 *
 * @throws UnsupportedOperationException when the host has no Web Audio at all, such as Node.js,
 *   when [technique] is [BrowserTechnique.Worklet] and the host has no audio worklets, or when
 *   the host cannot choose the output of [device].
 * @throws IllegalArgumentException for an invalid buffer size, a layout of more than two channels,
 *   a [device] that the last listing did not hold, or when the browser will not open a context
 *   at the format's rate.
 */
@Throws(UnsupportedOperationException::class, IllegalArgumentException::class)
internal fun <H : Any> openBrowserOutput(
    api: WebAudioApi<H>,
    format: AudioFormat,
    bufferFrames: Int,
    technique: BrowserTechnique,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    devices: BrowserOutputDevices<H>?,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput {
    requireSupportedLayout(format)
    validateBufferFrames(format, bufferFrames)
    val missing = when {
        !api.hasAudioContext() -> "this JavaScript host has no Web Audio"
        technique == BrowserTechnique.Worklet && !api.hasWorklet() ->
            "this page has no Web Audio worklets; it needs to be served from a secure context"
        else -> null
    }
    if (missing != null) {
        val problem = UnsupportedOperationException(missing)
        deliverAudioEvent(onEvent, AudioOutputEvent.Failed("no audio output is available", problem))
        throw problem
    }
    var sinkId: String? = null
    if (device != null) {
        val known = devices?.find(device.id)
        if (known == null) {
            val reason = unknownDeviceReason(device)
            val problem = IllegalArgumentException(reason)
            deliverAudioEvent(onEvent, AudioOutputEvent.Failed(reason, problem))
            throw problem
        }
        if (!known.isDefault) {
            if (!api.canChooseSink()) {
                val reason = "this browser cannot choose an audio output: its AudioContext takes no sinkId, so \"${device.name}\" cannot open"
                val problem = UnsupportedOperationException(reason)
                deliverAudioEvent(onEvent, AudioOutputEvent.Failed(reason, problem))
                throw problem
            }
            sinkId = known.id
        }
    }
    val sink = sinkId
    var firstFailure: Throwable? = null
    // The context does not depend on the channel count, so a rate that failed once fails again.
    val refused = HashSet<Int>()
    val opened = openFirstFormat(format, formatPolicy, emptyList()) { candidate ->
        if (candidate.sampleRate in refused) return@openFirstFormat null
        try {
            val context = if (sink == null) api.newContext(candidate.sampleRate) else api.newContextOn(candidate.sampleRate, sink)
            candidate to context
        } catch (problem: Throwable) {
            if (firstFailure == null) firstFailure = problem
            refused += candidate.sampleRate
            null
        }
    }
    if (opened == null) {
        val problem = firstFailure
        deliverAudioEvent(onEvent, AudioOutputEvent.Failed("no audio output is available", problem))
        throw IllegalArgumentException("Web Audio would not open a context at ${format.sampleRate} Hz", problem)
    }
    val (openedFormat, context) = opened
    val buffers = technique == BrowserTechnique.Buffers || (technique == BrowserTechnique.Auto && !api.hasWorklet())
    return WebAudioOutput(openedFormat, api, context, bufferFrames, fallBack = technique == BrowserTechnique.Auto, buffers, onEvent, watchSink = sink != null)
}

/**
 * Web Audio output, by one of two techniques, fed from the page's main thread.
 *
 * With a worklet, the processor hands each block it has played back to the page, and that return
 * asks for the next render, so the device's clock paces rendering. With scheduled buffers, a page
 * timer renders ahead and books each block as an `AudioBuffer` at an exact context time, which
 * needs no worklet but is paced by a page timer, which browsers slow in a hidden tab. [fallBack] lets a
 * worklet that will not load give way to buffers instead of failing.
 *
 * Everything here runs on one thread, the page's, so there is nothing to lock. Each start makes a
 * new node, which is what keeps a stopped generation's late messages and scheduled buffers from
 * reaching the next one.
 *
 * A stop by the app fades out. The next renders apply the fade, and the generation ends once
 * the block with the end of the fade has played. After that block the worklet or the gain node
 * has nothing more to play, so the output plays silence by itself.
 */
internal class WebAudioOutput<H : Any>(
    override val format: AudioFormat,
    private val api: WebAudioApi<H>,
    private val context: H,
    private val bufferFrames: Int,
    private val fallBack: Boolean,
    buffers: Boolean,
    private val onEvent: (AudioOutputEvent) -> Unit,
    /** True when the context plays on a chosen output, whose failure the context reports with an `error` event. */
    watchSink: Boolean = false,
) : AudioOutput {

    private enum class Load { Pending, Ready, Failed }

    private inner class Generation(val callback: AudioRenderCallback) {
        var active = true

        /** True once the context is running for this generation and [AudioOutputEvent.Started] has gone out. */
        var started = false
        var rendering = false
        var ended = false
        var node: H? = null
        var timer: H? = null
        var block: H? = null
        var nextTime = 0.0

        /** True when a render was short since the worklet last handed a block back. */
        var shortRender = false

        /** True once the app stopped the stream and its sound fades out. */
        var fading = false

        /** True once the block with the end of the fade is posted or booked. */
        var fadeDone = false

        /** The worklet slot of the block with the end of the fade. */
        var fadeEndSlot = -1

        /** The context time at which the booked block with the end of the fade has played. */
        var fadeEndTime = 0.0

        /** The timer that ends the fade if the browser stops playing. */
        var fallback: H? = null
        val observer: (AudioOutputEvent) -> Unit = { event ->
            if (event is AudioOutputEvent.Failed) active = false
            deliverAudioEvent(onEvent, event)
        }
    }

    /** True once this output plays by scheduled buffers, chosen or fallen back to. */
    private var buffers = buffers
    private var load = if (buffers) Load.Ready else Load.Pending
    private var loadFailure = ""

    /** Why the chosen output does not work, once the context said so. */
    private var sinkFailure: String? = null
    private var closed = false
    private var generation: Generation? = null
    private val deviceUnderruns = DeviceUnderrunCounter()
    private val scratch = FloatArray(bufferFrames * format.channels)
    private val deadlineMicros = format.framesToMicros(bufferFrames)
    private val blockSeconds = bufferFrames.toDouble() / format.sampleRate
    // Only one generation fades at a time.
    private val fade = StopFade(format.sampleRate, format.channels)

    // Capacity is an estimate of buffering: the blocks in flight and the context's own delay.
    override val latency: Latency
        get() {
            val base = runCatching { api.baseLatencySeconds(context) }.getOrDefault(0.0)
            return Latency(bufferFrames * WEB_AUDIO_BLOCKS + (base * format.sampleRate).roundToInt(), LatencyQuality.Estimated)
        }

    override val isRunning: Boolean get() = generation?.let { it.active && !it.fading } == true

    // A context at a requested rate hides the rate of the device, so the browser does not say.
    override val deviceFormat: AudioFormat? get() = null

    init {
        // Only this output's own end() suspends the context, and only once its generation is over, so a
        // suspension that reaches a started generation came from the browser, for a call or another
        // app taking the audio. Nothing would play, and nothing else would say so.
        api.onStateChange(context) { state ->
            val current = generation
            if (current != null && !closed) {
                // A stream in its fade sends no DeviceLost, because the app stopped it.
                when (state) {
                    "closed" -> end(current, lost(current, "the audio context closed"))
                    "suspended", "interrupted" ->
                        if (current.started) end(current, lost(current, "the browser $state the audio context"))
                }
            }
        }
        if (watchSink) {
            api.onError(context) { reason ->
                if (sinkFailure == null) {
                    sinkFailure = reason
                    // A stream that already plays has lost its device. Before that, the output never worked.
                    generation?.let { current ->
                        if (!closed) {
                            val text = "the audio output device failed: $reason"
                            end(current, if (current.started) lost(current, text) else AudioOutputEvent.Failed(text))
                        }
                    }
                }
            }
        }
        if (!buffers) {
            api.loadProcessor(
                context,
                WEB_AUDIO_WORKLET,
                done = {
                    load = Load.Ready
                    generation?.let { if (it.active && it.node == null) connect(it) }
                },
                failed = { reason ->
                    if (fallBack) {
                        this.buffers = true
                        load = Load.Ready
                        generation?.let { if (it.active && it.node == null) connect(it) }
                    } else {
                        load = Load.Failed
                        loadFailure = reason
                        generation?.let { end(it, AudioOutputEvent.Failed("the audio worklet would not load: $reason")) }
                    }
                },
            )
        }
    }

    override fun start(callback: AudioRenderCallback) {
        check(!closed) { "the audio output is closed" }
        val previous = generation
        if (previous != null && previous.active && !previous.fading) {
            // A start before the page may play sound leaves resume() waiting, and the browser
            // will not let it finish by itself. A start from the user's click asks again.
            if (!previous.started && previous.node != null) resume(previous)
            return
        }
        check(previous == null) { "the previous audio callback is still stopping" }
        val next = Generation(callback)
        generation = next
        sinkFailure?.let { reason ->
            end(next, AudioOutputEvent.Failed("the audio output device failed: $reason"))
            return
        }
        when (load) {
            Load.Ready -> connect(next)
            Load.Failed -> end(next, AudioOutputEvent.Failed("the audio worklet would not load: $loadFailure"))
            Load.Pending -> Unit // connect when the worklet has loaded
        }
    }

    private fun connect(current: Generation) {
        val node = try {
            if (buffers) api.newGain(context) else api.newNode(context, WEB_AUDIO_PROCESSOR, format.channels)
        } catch (problem: Throwable) {
            val what = if (buffers) "the audio output node" else "the audio worklet node"
            end(current, AudioOutputEvent.Failed("$what would not start", problem))
            return
        }
        current.node = node
        if (!buffers) {
            api.listen(
                node,
                returned = { slot, underrun, block -> returned(current, slot, underrun, block) },
                failed = { reason -> end(current, AudioOutputEvent.Failed("the audio worklet failed: $reason")) },
            )
        }
        resume(current)
    }

    /** Asks the browser to run the context. Every waiting request finishes once one succeeds, so only the first starts [current]. */
    private fun resume(current: Generation) {
        api.resume(
            context,
            done = { running(current) },
            failed = { reason -> end(current, AudioOutputEvent.Failed("the audio context would not resume: $reason")) },
        )
    }

    private fun running(current: Generation) {
        if (!current.active || current.started) return
        current.started = true
        deliverAudioEvent(onEvent, AudioOutputEvent.Started(format, latency))
        if (buffers) {
            current.nextTime = api.currentTime(context) + blockSeconds
            current.timer = api.startTimer(maxOf(1, (blockSeconds * 1000).toInt())) { schedule(current) }
            schedule(current)
        } else {
            for (slot in 0 until WEB_AUDIO_BLOCKS) {
                if (!renderAndPost(current, slot, null)) return
            }
        }
    }

    /**
     * Renders the next block into the one the worklet handed back. Silence that the worklet played
     * after a short render is the callback's, and goes out as an underrun in frames. Silence after
     * full renders is one device underrun, sent once the next block is posted.
     */
    private fun returned(current: Generation, slot: Int, underrunFrames: Int, block: H) {
        if (!current.active) return
        // The fade and its play-out count nothing. The generation ends once its last faded block is back.
        if (current.fading) {
            if (!current.fadeDone) renderAndPost(current, slot, block)
            else if (slot == current.fadeEndSlot) end(current, null)
            return
        }
        val afterShortRender = current.shortRender
        current.shortRender = false
        if (underrunFrames > 0 && afterShortRender) deliverAudioEvent(onEvent, AudioOutputEvent.Underrun(underrunFrames))
        if (!renderAndPost(current, slot, block)) return
        // A short render already sent Underrun, so its block counts no device underrun.
        if (underrunFrames > 0 && !afterShortRender && !current.shortRender && current.active && !current.fading) deviceUnderruns.report(1, onEvent)
    }

    /**
     * Books blocks until [WEB_AUDIO_BLOCKS] of them lie ahead of the context's clock. A timer that
     * fired too late to keep the next block's start in the future is one device underrun, sent
     * once the next block is booked. Scheduling restarts a block ahead of now.
     */
    private fun schedule(current: Generation) {
        if (!current.active) return
        val now = api.currentTime(context)
        if (current.fadeDone) {
            // The fade ends once its last booked block has played.
            if (now >= current.fadeEndTime) end(current, null)
            return
        }
        var ranDry = false
        if (current.nextTime < now) {
            // The fade counts nothing.
            ranDry = !current.fading
            current.nextTime = now + blockSeconds
        }
        // Half a frame short of the horizon, so a block that would start exactly on it waits for the next tick.
        val horizon = now + blockSeconds * WEB_AUDIO_BLOCKS - 0.5 / format.sampleRate
        while (current.nextTime < horizon && !current.fadeDone) {
            current.shortRender = false
            if (!render(current)) return
            val node = current.node ?: return
            try {
                val block = current.block ?: api.newBlock(scratch.size).also { current.block = it }
                api.fill(block, scratch)
                api.playBuffer(context, node, format.channels, bufferFrames, block, current.nextTime)
            } catch (problem: Throwable) {
                end(current, AudioOutputEvent.Failed("Web Audio would not play a buffer", problem))
                return
            }
            current.nextTime += blockSeconds
            if (current.fadeDone) current.fadeEndTime = current.nextTime
            if (ranDry) {
                ranDry = false
                // A short render already sent Underrun, so its block counts no device underrun.
                if (!current.shortRender && !current.fading) deviceUnderruns.report(1, onEvent)
            }
        }
    }

    /** Renders one block into [scratch]; false, with the generation ended, when it should go no further. */
    private fun render(current: Generation): Boolean {
        if (!current.active) {
            end(current, null)
            return false
        }
        current.rendering = true
        val filled = try {
            renderAudioBlock(current.callback, scratch, bufferFrames, deadlineMicros, current.observer)
        } finally {
            current.rendering = false
        }
        if (filled in 0 until bufferFrames) current.shortRender = true
        if (filled == RENDER_FAILED || !current.active || current.node == null) {
            end(current, null)
            return false
        }
        // A stop by the app, also one from inside this render, fades this block and the next ones out.
        if (current.fading && fade.apply(scratch, bufferFrames)) current.fadeDone = true
        return true
    }

    /**
     * Renders one block and hands it to the worklet, reusing [recycled] when the processor gave one back.
     * False when nothing more should be rendered, also after the block with the end of the fade.
     */
    private fun renderAndPost(current: Generation, slot: Int, recycled: H?): Boolean {
        if (current.fadeDone || !render(current)) return false
        val node = current.node ?: return false
        try {
            val block = recycled ?: api.newBlock(scratch.size)
            api.fill(block, scratch)
            api.post(node, slot, block)
        } catch (problem: Throwable) {
            end(current, AudioOutputEvent.Failed("the audio worklet would not take a block", problem))
            return false
        }
        if (current.fadeDone) {
            current.fadeEndSlot = slot
            return false
        }
        return true
    }

    /** The event for a system end of [current]: DeviceLost, or none during a fade, because the app stopped the stream. */
    private fun lost(current: Generation, reason: String): AudioOutputEvent? =
        if (current.fading) null else AudioOutputEvent.DeviceLost(reason)

    /**
     * Reports [reason], if any, and ends [current]: the timer stopped, the node released, the
     * context suspended and [AudioOutputEvent.Stopped] delivered. A generation still inside its
     * render callback ends when that returns, so Stopped always follows the last render.
     */
    private fun end(current: Generation, reason: AudioOutputEvent?) {
        if (current.ended) return
        current.active = false
        if (reason != null) {
            deliverAudioEvent(onEvent, reason)
            // The observer may have stopped or closed the output, which has ended this generation already.
            if (current.ended) return
        }
        if (current.rendering) return
        current.ended = true
        current.timer?.let { runCatching { api.stopTimer(it) } }
        current.timer = null
        current.fallback?.let { runCatching { api.stopTimer(it) } }
        current.fallback = null
        current.node?.let { runCatching { if (buffers) api.disconnect(it) else api.release(it) } }
        current.node = null
        if (!closed) runCatching { api.suspend(context) }
        try {
            deliverAudioEvent(onEvent, AudioOutputEvent.Stopped)
        } finally {
            if (generation === current) generation = null
        }
    }

    override fun stop() {
        val current = generation ?: return
        // A stream that plays fades out. One that has not started yet has nothing to fade.
        if (!current.started || !current.active) {
            end(current, null)
            return
        }
        if (current.fading) return
        current.fading = true
        fade.reset()
        // Twice the latency and the fade, plus 100 ms, for a browser that stops playing.
        val frames = 2L * (latency.frames + fade.frames)
        val millis = (frames * 1000 / format.sampleRate + 100).toInt()
        current.fallback = runCatching {
            api.startTimer(millis) { end(current, null) }
        }.getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation?.let { end(it, null) }
        runCatching { api.close(context) }
    }
}
