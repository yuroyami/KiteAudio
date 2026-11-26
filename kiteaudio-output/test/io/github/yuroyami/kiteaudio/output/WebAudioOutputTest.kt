package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The browser output, driven through a stand-in for Web Audio. The stand-in holds every callback the
 * output gives it, so a test decides when the worklet loads, when the context resumes and when the
 * processor hands a block back.
 */
class WebAudioOutputTest {

    private class Handle(val kind: String, val samples: FloatArray = FloatArray(0))

    private class FakeWebAudio(var context: Boolean = true, var worklet: Boolean = true) : WebAudioApi<Handle> {
        var loaded: (() -> Unit)? = null
        var loadFailed: ((String) -> Unit)? = null
        var stateChanged: ((String) -> Unit)? = null
        /** Every resume still waiting. A browser finishes them all together once one may run. */
        val waiting = mutableListOf<() -> Unit>()
        var resumes = 0
        val resumed: (() -> Unit)?
            get() = if (waiting.isEmpty()) null else ({
                val all = waiting.toList()
                waiting.clear()
                all.forEach { it() }
            })
        var returned: ((Int, Int, Handle) -> Unit)? = null
        var processorFailed: ((String) -> Unit)? = null
        val nodes = mutableListOf<Handle>()
        val released = mutableListOf<Handle>()
        val posted = mutableListOf<Pair<Int, Handle>>()
        var newBlocks = 0
        var loads = 0
        var now = 0.0
        /** Every timer that runs, with its interval and its tick, in the order they started. */
        val timers = LinkedHashMap<Handle, Pair<Int, () -> Unit>>()
        /** The oldest timer: the one that books scheduled buffers. */
        val tick: (() -> Unit)? get() = timers.values.firstOrNull()?.second
        /** The newest timer: the fallback of a stop. */
        val lastTimer: Pair<Int, () -> Unit>? get() = timers.values.lastOrNull()
        var timersStopped = 0
        /** The blocks posted to the worklet that it has not handed back yet, oldest first. */
        val inFlight = ArrayDeque<Pair<Int, Handle>>()
        /** A copy of the samples of every posted block, and of every scheduled buffer. */
        val postedSamples = mutableListOf<FloatArray>()
        val playedSamples = mutableListOf<FloatArray>()
        val gains = mutableListOf<Handle>()
        val disconnected = mutableListOf<Handle>()
        /** Every scheduled buffer: its start time and its first sample. */
        val played = mutableListOf<Pair<Double, Float>>()
        var suspends = 0
        var closes = 0

        override fun hasAudioContext() = context
        override fun hasWorklet() = worklet
        override fun currentTime(context: Handle) = now
        override fun newGain(context: Handle) = Handle("gain").also { gains += it }
        override fun disconnect(node: Handle) { disconnected += node }
        override fun playBuffer(context: Handle, destination: Handle, channels: Int, frames: Int, block: Handle, at: Double) {
            played += at to block.samples[0]
            playedSamples += block.samples.copyOf()
        }
        override fun startTimer(intervalMs: Int, tick: () -> Unit): Handle = Handle("timer").also { timers[it] = intervalMs to tick }
        override fun stopTimer(timer: Handle) { timersStopped++; timers.remove(timer) }
        override fun newContext(sampleRate: Int) = Handle("context")
        override fun baseLatencySeconds(context: Handle) = 0.01
        override fun loadProcessor(context: Handle, source: String, done: () -> Unit, failed: (String) -> Unit) {
            loads++
            loaded = done
            loadFailed = failed
        }
        override fun onStateChange(context: Handle, changed: (String) -> Unit) { stateChanged = changed }
        override fun resume(context: Handle, done: () -> Unit, failed: (String) -> Unit) { resumes++; waiting += done }
        override fun suspend(context: Handle) { suspends++ }
        override fun close(context: Handle) { closes++ }
        override fun newNode(context: Handle, name: String, channels: Int) = Handle("node").also { nodes += it }
        override fun listen(node: Handle, returned: (Int, Int, Handle) -> Unit, failed: (String) -> Unit) {
            this.returned = returned
            processorFailed = failed
        }
        override fun release(node: Handle) { released += node; inFlight.clear() }
        override fun newBlock(samples: Int): Handle { newBlocks++; return Handle("block", FloatArray(samples)) }
        override fun fill(block: Handle, samples: FloatArray) { samples.copyInto(block.samples) }
        override fun post(node: Handle, slot: Int, block: Handle) {
            posted += slot to block
            inFlight.addLast(slot to block)
            postedSamples += block.samples.copyOf()
        }

        /** Hands back the oldest block in flight, as the processor does, with [silence] frames played since the last one. */
        fun handBackOldest(silence: Int = 0) {
            val (slot, block) = inFlight.removeFirst()
            returned!!(slot, silence, block)
        }

        /** Loads the worklet and resumes the context, as a browser does once the page may play. */
        fun play() {
            loaded?.invoke()
            resumed?.invoke()
        }
    }

    private val format = AudioFormat(48_000, ChannelLayout.STEREO)
    private val web = FakeWebAudio()
    private val events = mutableListOf<AudioOutputEvent>()
    private var rendered = 0

    private val counting = AudioRenderCallback { out, frames ->
        rendered++
        out.fill(rendered.toFloat(), 0, frames * 2)
        frames
    }

    private fun open(bufferFrames: Int = 256, technique: BrowserTechnique = BrowserTechnique.Worklet) =
        openBrowserOutput(web, format, bufferFrames, technique) { events += it }

    /**
     * Plays out the fade of a stop until [events] gets one more Stopped: hands back the blocks in
     * flight to the worklet, or moves the clock on and lets the timer book and play the buffers.
     */
    private fun FakeWebAudio.finishFade(events: List<AudioOutputEvent>, step: Double = 256.0 / 48_000) {
        val stops = events.count { it == AudioOutputEvent.Stopped }
        repeat(100) {
            if (events.count { it == AudioOutputEvent.Stopped } > stops) return
            if (inFlight.isNotEmpty()) handBackOldest() else { now += step; tick?.invoke() }
        }
        error("the fade did not end: $events")
    }

    @Test
    fun startingBeforeTheWorkletLoadsWaitsThenFillsEveryBlock() {
        val output = open()
        output.start(counting)
        assertTrue(web.nodes.isEmpty(), "a node was made before the worklet loaded")

        web.play()

        val started = assertIs<AudioOutputEvent.Started>(events.single())
        assertEquals(256 * WEB_AUDIO_BLOCKS + 480, started.latency.frames)
        assertEquals(LatencyQuality.Estimated, started.latency.quality)
        assertEquals((0 until WEB_AUDIO_BLOCKS).toList(), web.posted.map { it.first })
        assertEquals(listOf(1f, 2f, 3f, 4f), web.posted.map { it.second.samples[0] })
        assertTrue(output.isRunning)
    }

    @Test
    fun aReturnedBlockIsRenderedAgainInPlaceAndItsSilenceReported() {
        open().start(counting)
        web.play()
        val (slot, block) = web.posted[2]

        web.returned!!(slot, 128, block)

        // Every render was full, so the silence is the device's.
        assertEquals(AudioOutputEvent.DeviceUnderrun(1, 1), events.last())
        assertEquals(slot to block, web.posted.last())
        assertEquals(5f, block.samples[0])
        assertEquals(WEB_AUDIO_BLOCKS, web.newBlocks, "a returned block was not reused")
    }

    @Test
    fun stoppingInsideTheRendererFadesThatBlockAndEndsOnceItHasPlayed() {
        lateinit var output: AudioOutput
        var restart: Throwable? = null
        output = open()
        output.start { out, frames ->
            rendered++
            out.fill(0.5f)
            if (rendered == 2) {
                output.stop()
                assertTrue(!output.isRunning, "isRunning was true after stop returned")
                restart = runCatching { output.start(counting) }.exceptionOrNull()
            }
            frames
        }
        web.play()

        assertIs<IllegalStateException>(restart, "a start during the fade was not rejected")
        // A fade of 240 frames ends inside the block of 256 frames that called stop.
        assertEquals(2, rendered)
        assertEquals(2, web.posted.size, "the faded block was not posted")
        assertFaded(web.postedSamples[1].everyOther(), 0.5f, 256)
        assertTrue(AudioOutputEvent.Stopped !in events, "stopped before the faded block played")

        web.handBackOldest()
        assertTrue(AudioOutputEvent.Stopped !in events, "stopped before the faded block played")
        web.handBackOldest()
        assertEquals(AudioOutputEvent.Stopped, events.last())
        assertEquals(1, events.count { it == AudioOutputEvent.Stopped })
        assertEquals(web.nodes, web.released)
        assertEquals(1, web.suspends)

        web.returned!!(0, 0, web.posted[0].second)
        assertEquals(2, rendered, "a block returned after stop was rendered")
    }

    @Test
    fun aThrowingRendererFailsThenStopsAndTheSinkCanStartAgain() {
        val output = open()
        output.start { _, _ -> error("broken") }
        web.play()

        assertIs<AudioOutputEvent.Failed>(events[1])
        assertEquals(AudioOutputEvent.Stopped, events[2])
        assertTrue(!output.isRunning)

        output.start(counting)
        web.resumed!!()
        assertEquals(2, web.nodes.size, "a restart did not make a new node")
        assertIs<AudioOutputEvent.Started>(events[3])
    }

    @Test
    fun restartingFromTheStoppedObserverIsRejected() {
        lateinit var output: AudioOutput
        var rejected: Throwable? = null
        output = openBrowserOutput(web, format, 256, BrowserTechnique.Worklet) { event ->
            events += event
            if (event == AudioOutputEvent.Stopped) rejected = runCatching { output.start(counting) }.exceptionOrNull()
        }
        output.start(counting)
        web.play()
        output.stop()
        web.finishFade(events)

        assertIs<IllegalStateException>(rejected)
        output.start(counting)
        assertTrue(output.isRunning)
    }

    @Test
    fun aWorkletThatWillNotLoadFailsTheStart() {
        val output = open()
        web.loadFailed!!("blocked by policy")
        output.start(counting)

        val failed = assertIs<AudioOutputEvent.Failed>(events[0])
        assertTrue("blocked by policy" in failed.reason, failed.reason)
        assertEquals(AudioOutputEvent.Stopped, events[1])
    }

    @Test
    fun aProcessorErrorFailsTheStream() {
        open().start(counting)
        web.play()
        web.processorFailed!!("the processor threw")

        assertIs<AudioOutputEvent.Failed>(events[events.size - 2])
        assertEquals(AudioOutputEvent.Stopped, events.last())
    }

    @Test
    fun aContextClosedUnderTheSinkIsADeviceLoss() {
        open().start(counting)
        web.play()
        web.stateChanged!!("closed")

        assertIs<AudioOutputEvent.DeviceLost>(events[events.size - 2])
        assertEquals(AudioOutputEvent.Stopped, events.last())
    }

    @Test
    fun aContextTheBrowserSuspendsOrInterruptsIsADeviceLoss() {
        for (state in listOf("suspended", "interrupted")) {
            events.clear()
            for (technique in listOf(BrowserTechnique.Worklet, BrowserTechnique.Buffers)) {
                val output = open(technique = technique)
                output.start(counting)
                web.play()
                web.stateChanged!!(state)

                val lost = assertIs<AudioOutputEvent.DeviceLost>(events[events.size - 2], "$state, $technique")
                assertEquals("the browser $state the audio context", lost.reason)
                assertEquals(AudioOutputEvent.Stopped, events.last())
                assertTrue(!output.isRunning)
                output.close()
            }
        }
    }

    @Test
    fun aSuspensionBeforeTheStreamStartsOrAfterItStopsIsNotALoss() {
        val output = open()
        output.start(counting)
        web.stateChanged!!("suspended")
        assertTrue(events.isEmpty(), "the wait for the page to be allowed to play was reported")

        web.play()
        output.stop()
        web.finishFade(events)
        val stopped = events.size
        web.stateChanged!!("suspended")
        assertEquals(stopped, events.size, "the output's own suspend was reported")

        output.start(counting)
        web.stateChanged!!("suspended")
        assertEquals(stopped, events.size, "a late suspend from the last stop reached the next start")
        web.resumed!!()
        assertIs<AudioOutputEvent.Started>(events.last())
    }

    @Test
    fun aStartBeforeThePageMayPlayAsksAgainWhenStartedAgain() {
        val output = open()
        output.start(counting)
        web.loaded!!()
        assertEquals(1, web.resumes)

        // The page's first click: the app calls start again, and the output asks the browser again.
        output.start(counting)
        assertEquals(2, web.resumes)
        web.resumed!!()

        assertEquals(1, events.count { it is AudioOutputEvent.Started }, "started once for each waiting resume")
        assertEquals(WEB_AUDIO_BLOCKS, web.posted.size)
        assertTrue(output.isRunning)

        output.start(counting)
        assertEquals(2, web.resumes, "a start while playing asked the browser again")
    }

    @Test
    fun aHandlerThatStopsOrClosesOnALossOrFailureSeesOneStopped() {
        for (closing in listOf(false, true)) {
            for (trouble in listOf<(FakeWebAudio) -> Unit>({ it.stateChanged!!("interrupted") }, { it.processorFailed!!("the processor threw") })) {
                val web = FakeWebAudio()
                val events = mutableListOf<AudioOutputEvent>()
                lateinit var output: AudioOutput
                output = openBrowserOutput(web, format, 256, BrowserTechnique.Worklet) { event ->
                    events += event
                    if (event is AudioOutputEvent.DeviceLost || event is AudioOutputEvent.Failed) {
                        if (closing) output.close() else output.stop()
                    }
                }
                output.start(counting)
                web.play()
                trouble(web)

                assertEquals(1, events.count { it == AudioOutputEvent.Stopped }, "closing $closing: $events")
                assertEquals(AudioOutputEvent.Stopped, events.last())
                assertEquals(1, web.released.size)
            }
        }
    }

    @Test
    fun closingStopsClosesTheContextAndRefusesAnotherStart() {
        val output = open()
        output.start(counting)
        web.play()
        output.close()
        output.close()

        assertEquals(AudioOutputEvent.Stopped, events.last())
        assertEquals(1, web.closes)
        web.stateChanged!!("closed")
        assertEquals(AudioOutputEvent.Stopped, events.last(), "closing it ourselves was reported as a loss")
        assertFailsWith<IllegalStateException> { output.start(counting) }
    }

    @Test
    fun aHostWithoutWorkletsRefusesTheWorkletButNotTheOthers() {
        web.worklet = false
        assertFailsWith<UnsupportedOperationException> { open() }
        assertIs<AudioOutputEvent.Failed>(events.single())

        open(technique = BrowserTechnique.Auto).start(counting)
        web.resumed!!()
        assertEquals(0, web.loads, "auto loaded a worklet the page cannot run")
        assertEquals(1, web.gains.size)
        assertTrue(web.played.isNotEmpty())
    }

    @Test
    fun aHostWithoutWebAudioRefusesEveryTechnique() {
        web.context = false
        for (technique in BrowserTechnique.entries) assertFailsWith<UnsupportedOperationException> { open(technique = technique) }
    }

    @Test
    fun autoFallsBackToBuffersWhenTheWorkletWillNotLoad() {
        val output = open(technique = BrowserTechnique.Auto)
        output.start(counting)
        web.loadFailed!!("blocked by policy")
        web.resumed!!()

        assertIs<AudioOutputEvent.Started>(events.single())
        assertTrue(web.nodes.isEmpty(), "a worklet node was made after the worklet failed")
        assertEquals(1, web.gains.size)
        assertTrue(web.played.isNotEmpty())
    }

    private val blockSeconds = 256.0 / 48_000

    /** Context times agree to well under a frame. */
    private fun assertTimes(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size, "booked at $actual, expected $expected")
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) < 1e-9, "booked at $actual, expected $expected")
    }

    @Test
    fun buffersAreBookedBackToBackFourBlocksAhead() {
        web.now = 10.0
        open(technique = BrowserTechnique.Buffers).start(counting)
        web.resumed!!()

        assertIs<AudioOutputEvent.Started>(events.single())
        assertEquals(0, web.loads)
        assertEquals(listOf(1f, 2f, 3f), web.played.map { it.second })
        assertTimes(listOf(10 + blockSeconds, 10 + 2 * blockSeconds, 10 + 3 * blockSeconds), web.played.map { it.first })
        assertEquals(1, web.newBlocks, "the copy block was not reused")

        web.now = 10 + 1.5 * blockSeconds
        web.tick!!()
        assertTimes(listOf(10 + 4 * blockSeconds, 10 + 5 * blockSeconds), web.played.drop(3).map { it.first })
        assertEquals(5, web.played.size)
        assertEquals(1, events.size, "an on-time tick reported something")
    }

    @Test
    fun aLateTimerCountsTheSilenceAndStartsAgainAhead() {
        open(technique = BrowserTechnique.Buffers).start(counting)
        web.resumed!!()
        val booked = web.played.size

        web.now = 5 * blockSeconds
        web.tick!!()

        // Booked blocks ran out at 4 blocks and the next starts at 6: one dropout.
        assertEquals(AudioOutputEvent.DeviceUnderrun(1, 1), events.last())
        assertTimes(listOf(6 * blockSeconds), web.played.subList(booked, booked + 1).map { it.first })
    }

    @Test
    fun stoppingBuffersStopsTheTimerAndSilencesWhatWasBooked() {
        val output = open(technique = BrowserTechnique.Buffers)
        output.start(counting)
        web.resumed!!()
        output.stop()
        assertTrue(!output.isRunning)
        web.finishFade(events)

        assertEquals(AudioOutputEvent.Stopped, events.last())
        // The timer that books the buffers, and the fallback of the stop.
        assertEquals(2, web.timersStopped)
        assertTrue(web.timers.isEmpty())
        assertEquals(web.gains, web.disconnected)
        assertEquals(1, web.suspends)
        assertTrue(!output.isRunning)
    }

    /** A renderer that fills every block, except the blocks whose numbers are in [short]. */
    private fun renderer(vararg short: Int) = AudioRenderCallback { out, frames ->
        rendered++
        out.fill(rendered.toFloat(), 0, frames * 2)
        if (rendered in short) frames - 1 else frames
    }

    /** Hands back the oldest block in flight, as the processor does, with [silence] frames played since the last one. */
    private fun handBack(silence: Int) = web.handBackOldest(silence)

    @Test
    fun workletSilenceAfterFullRendersIsOneDeviceUnderrunAfterTheNextPost() {
        var postedAtEvent = -1
        val output = openBrowserOutput(web, format, 256, BrowserTechnique.Worklet) { event ->
            events += event
            if (event is AudioOutputEvent.DeviceUnderrun) postedAtEvent = web.posted.size
        }
        output.start(counting)
        web.play()

        handBack(0)
        handBack(300)
        assertEquals(WEB_AUDIO_BLOCKS + 2, postedAtEvent, "the event came before the next block was posted")
        handBack(0)
        handBack(1)

        assertEquals(
            listOf(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)),
            events.filter { it is AudioOutputEvent.DeviceUnderrun || it is AudioOutputEvent.Underrun },
        )
    }

    @Test
    fun workletSilenceAfterAShortRenderStaysAnUnderrun() {
        // The fifth render is the first one after the four that prime the worklet.
        open().start(renderer(5))
        web.play()

        handBack(0)
        assertEquals(AudioOutputEvent.Underrun(1), events.last())
        handBack(200)

        assertEquals(listOf(AudioOutputEvent.Underrun(1), AudioOutputEvent.Underrun(200)), events.drop(1))
    }

    @Test
    fun aShortRenderAfterWorkletSilenceSendsOnlyItsUnderrun() {
        open().start(renderer(5))
        web.play()

        handBack(300)

        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Underrun(1)), events.drop(1))
    }

    @Test
    fun deviceUnderrunTotalsContinueAcrossStopAndStart() {
        for (technique in listOf(BrowserTechnique.Worklet, BrowserTechnique.Buffers)) {
            val web = FakeWebAudio()
            val events = mutableListOf<AudioOutputEvent>()
            val output = openBrowserOutput(web, format, 256, technique) { events += it }
            repeat(2) {
                output.start(counting)
                web.play()
                if (technique == BrowserTechnique.Worklet) {
                    web.handBackOldest(64)
                } else {
                    web.now += 10 * blockSeconds
                    web.tick!!()
                }
                output.stop()
                web.finishFade(events)
            }

            assertEquals(
                listOf(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)),
                events.filterIsInstance<AudioOutputEvent.DeviceUnderrun>(),
                "$technique",
            )
            assertTrue(events.none { it is AudioOutputEvent.Underrun }, "$technique: $events")
        }
    }

    @Test
    fun aLateTimerSendsOneDeviceUnderrunAfterTheFirstBlockItBooks() {
        var bookedAtEvent = -1
        val output = openBrowserOutput(web, format, 256, BrowserTechnique.Buffers) { event ->
            events += event
            if (event is AudioOutputEvent.DeviceUnderrun) bookedAtEvent = web.played.size
        }
        output.start(counting)
        web.resumed!!()
        val booked = web.played.size

        web.now = 5 * blockSeconds
        web.tick!!()
        assertEquals(booked + 1, bookedAtEvent, "the event did not follow the first block of the late tick")
        assertEquals(1, events.count { it is AudioOutputEvent.DeviceUnderrun }, "a late tick that booked ${web.played.size - booked} blocks")

        web.now += blockSeconds
        web.tick!!()
        web.now += 20 * blockSeconds
        web.tick!!()

        assertEquals(
            listOf(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)),
            events.filter { it is AudioOutputEvent.DeviceUnderrun || it is AudioOutputEvent.Underrun },
        )
    }

    @Test
    fun aLateTimerWhoseNextRenderIsShortSendsOnlyTheUnderrun() {
        // Three blocks are booked at the start, so the fourth render is the first of the late tick.
        open(technique = BrowserTechnique.Buffers).start(renderer(4))
        web.resumed!!()

        web.now = 5 * blockSeconds
        web.tick!!()

        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Underrun(1)), events.drop(1))
    }

    @Test
    fun aLayoutOfMoreThanTwoChannelsIsRefusedBeforeAnythingOpens() {
        assertFailsWith<IllegalArgumentException> {
            openBrowserOutput(web, AudioFormat(48_000, 6), 256, BrowserTechnique.Worklet) { events += it }
        }
        assertTrue(events.isEmpty())
        assertEquals(0, web.loads)
    }

    @Test
    fun anInvalidBufferSizeIsRefusedBeforeAnythingOpens() {
        assertFailsWith<IllegalArgumentException> { open(bufferFrames = 0) }
        assertTrue(events.isEmpty())
    }

    private val fade = StopFade(48_000, 2)

    /** The left channel of interleaved stereo samples. */
    private fun FloatArray.everyOther(): List<Float> = filterIndexed { index, _ -> index % 2 == 0 }

    /** Checks that [left] ramps down from [level] to exactly zero over the fade, then holds zeros, [frames] frames in all. */
    private fun assertFaded(left: List<Float>, level: Float, frames: Int) {
        assertEquals(frames, left.size)
        // A FloatArray rounds to 32 bits as the output's buffers do. On Kotlin/JS a Float product is a double until then.
        assertEquals(FloatArray(fade.frames) { level * fade.gain(it) }.toList(), left.subList(0, fade.frames))
        assertEquals(0f, left[fade.frames - 1], "the fade does not end at zero")
        assertEquals(List(frames - fade.frames) { 0f }, left.subList(fade.frames, frames))
    }

    private val half = AudioRenderCallback { out, frames ->
        rendered++
        out.fill(0.5f, 0, frames * 2)
        frames
    }

    @Test
    fun aWorkletFadeLongerThanABlockSpansBlocksAndCountsNoUnderrun() {
        val output = open(bufferFrames = 100)
        output.start(half)
        web.play()
        assertEquals(WEB_AUDIO_BLOCKS, rendered)

        output.stop()
        assertTrue(!output.isRunning)
        assertEquals(WEB_AUDIO_BLOCKS, rendered, "stop rendered at once")
        // Each block handed back during the fade asks for the next faded render, with silence that counts nothing.
        repeat(3) { web.handBackOldest(silence = 50) }
        assertEquals(WEB_AUDIO_BLOCKS + 3, rendered)
        val faded = web.postedSamples.takeLast(3).flatMap { it.everyOther() }
        assertFaded(faded, 0.5f, 300)

        // The blocks before the last faded one come back and render nothing.
        repeat(WEB_AUDIO_BLOCKS - 1) { web.handBackOldest(silence = 50) }
        assertEquals(WEB_AUDIO_BLOCKS + 3, rendered)
        assertTrue(AudioOutputEvent.Stopped !in events, "stopped before the faded block played")
        web.handBackOldest(silence = 50)
        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.drop(1))
        assertEquals(1, web.timersStopped, "the fallback timer was not stopped")
    }

    @Test
    fun aBuffersFadeIsBookedThenEndsOnceItHasPlayed() {
        web.now = 10.0
        val step = 100.0 / 48_000
        val output = open(bufferFrames = 100, technique = BrowserTechnique.Buffers)
        output.start(half)
        web.resumed!!()
        val booked = web.played.size
        output.stop()
        assertTrue(!output.isRunning)

        // A late tick during the fade counts no device underrun.
        web.now += 10 * step
        web.tick!!()
        assertEquals(booked + 3, web.played.size, "the fade was not booked in three blocks")
        assertFaded(web.playedSamples.takeLast(3).flatMap { it.everyOther() }, 0.5f, 300)
        val fadeEnd = web.played.last().first + step
        web.now = fadeEnd - step / 2
        web.tick!!()
        assertTrue(AudioOutputEvent.Stopped !in events, "stopped before the faded block played")
        assertEquals(booked + 3, web.played.size, "silence was booked after the fade")
        web.now = fadeEnd
        web.tick!!()
        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.drop(1))
    }

    @Test
    fun aSystemEndDuringTheFadeSendsNoDeviceLost() {
        for (end in listOf<(FakeWebAudio) -> Unit>({ it.stateChanged!!("suspended") }, { it.stateChanged!!("interrupted") }, { it.stateChanged!!("closed") })) {
            for (technique in listOf(BrowserTechnique.Worklet, BrowserTechnique.Buffers)) {
                val web = FakeWebAudio()
                val events = mutableListOf<AudioOutputEvent>()
                val output = openBrowserOutput(web, format, 256, technique) { events += it }
                output.start(counting)
                web.play()
                output.stop()
                end(web)

                assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.drop(1), "$technique")
                assertTrue(!output.isRunning)
                output.close()
            }
        }
    }

    @Test
    fun aCloseDuringTheFadeEndsAtOnce() {
        val output = open()
        output.start(counting)
        web.play()
        output.stop()
        output.close()

        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.drop(1))
        assertEquals(web.nodes, web.released)
        assertEquals(1, web.closes)
        web.returned!!(0, 0, web.posted[0].second)
        assertEquals(WEB_AUDIO_BLOCKS, rendered, "a block was rendered after the close")
    }

    @Test
    fun theFallbackTimerEndsAFadeThatNeverPlays() {
        for (technique in listOf(BrowserTechnique.Worklet, BrowserTechnique.Buffers)) {
            val web = FakeWebAudio()
            val events = mutableListOf<AudioOutputEvent>()
            val output = openBrowserOutput(web, format, 256, technique) { events += it }
            output.start(counting)
            web.play()
            output.stop()
            // Twice the latency of 1504 frames and the fade of 240 frames, plus 100 ms.
            val (interval, fire) = web.lastTimer!!
            assertEquals(2 * (1504 + 240) * 1000 / 48_000 + 100, interval, "$technique")
            assertTrue(AudioOutputEvent.Stopped !in events)

            fire()
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.drop(1), "$technique")
            assertTrue(web.timers.isEmpty(), "$technique: a timer still runs")
            output.start(counting)
            assertTrue(output.isRunning)
            output.close()
        }
    }

    @Test
    fun aRenderFailureDuringTheFadeEndsWithFailed() {
        var fail = false
        val output = open()
        output.start { out, frames -> if (fail) error("broken") else { out.fill(0.5f); frames } }
        web.play()
        output.stop()
        fail = true
        web.handBackOldest()

        assertIs<AudioOutputEvent.Failed>(events[1])
        assertEquals(AudioOutputEvent.Stopped, events[2])
        assertEquals(3, events.size)
    }

    @Test
    fun aStopBeforeTheStreamStartsEndsAtOnce() {
        val output = open()
        output.start(counting)
        output.stop()
        assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events)
        assertTrue(web.timers.isEmpty())
    }
}
