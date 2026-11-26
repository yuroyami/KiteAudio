package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Deterministic output doubles: these tests never discover or open a physical audio device. */
class JvmAudioOutputRegressionTest {
    private class FakeDevice {
        val writes = AtomicInteger()
        val starts = AtomicInteger()
        val stopped = CountDownLatch(1)
        var startFailure = false
        var writer: (ByteArray, Int, Int) -> Int = { _, _, count -> Thread.sleep(1); count }
        @Volatile var bufferSize = 4096
        /** The free bytes of the line for the number of writes so far. */
        @Volatile var free: (Int) -> Int = { 0 }
        val availableCalls = AtomicInteger()
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "start" -> { starts.incrementAndGet(); check(!startFailure) { "start failure" }; null }
                "write" -> { writes.incrementAndGet(); writer(args!![0] as ByteArray, args[1] as Int, args[2] as Int) }
                "stop", "close" -> { stopped.countDown(); null }
                "getBufferSize" -> bufferSize
                "available" -> { availableCalls.incrementAndGet(); free(writes.get()) }
                "isOpen", "isRunning", "isActive" -> true
                "getLongFramePosition", "getMicrosecondPosition" -> 0L
                "getFramePosition" -> 0
                "getLevel" -> 0f
                else -> null
            }
        } as SourceDataLine
        fun open(bufferFrames: Int = 4, observer: (AudioOutputEvent) -> Unit = {}): AudioOutput =
            JvmAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), line, bufferFrames, observer)

        /** Records every byte that the line takes, and takes all of it at once. */
        fun record(): MutableList<Byte> {
            val taken = mutableListOf<Byte>()
            writer = { data, offset, count -> synchronized(taken) { for (i in offset until offset + count) taken += data[i] }; count }
            return taken
        }
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS), "worker did not reach expected state") }

    private fun startAfterUnwind(output: AudioOutput, callback: AudioRenderCallback) {
        repeat(3000) {
            try { output.start(callback); return } catch (_: IllegalStateException) { Thread.sleep(1) }
        }
        error("generation did not finish unwinding")
    }

    @Test
    fun terminalObserverCompletesBeforeTheNextStartedEvent() {
        val device = FakeDevice()
        val terminalEntered = CountDownLatch(1)
        val terminalRelease = CountDownLatch(1)
        val starts = AtomicInteger()
        val output = device.open {
            if (it is AudioOutputEvent.Started) starts.incrementAndGet()
            if (it is AudioOutputEvent.Stopped) { terminalEntered.countDown(); await(terminalRelease) }
        }
        try {
            output.start { _, n -> output.stop(); n }
            await(terminalEntered)
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
            assertEquals(1, starts.get())
            terminalRelease.countDown()
            val fresh = CountDownLatch(1)
            startAfterUnwind(output) { _, n -> fresh.countDown(); n }
            await(fresh)
            assertEquals(2, starts.get())
        } finally { terminalRelease.countDown(); output.close() }
    }

    @Test
    fun boundedStopRetainsOwnershipUntilTheOldRendererUnwinds() {
        val device = FakeDevice()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val calls = AtomicInteger()
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { _, frames -> calls.incrementAndGet(); entered.countDown(); await(release); frames }
            await(entered)
            output.stop()
            assertFalse(output.isRunning)
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
            release.countDown()
            await(ended)
            assertEquals(1, calls.get())
            assertEquals(0, device.writes.get(), "a canceled renderer submitted stale samples")
            val restarted = CountDownLatch(1)
            startAfterUnwind(output) { _, frames -> restarted.countDown(); frames }
            await(restarted)
        } finally { release.countDown(); output.close() }
    }

    @Test
    fun aStopWhoseWriteNeverReturnsUnblocksItAfterTheBoundedWait() {
        val device = FakeDevice()
        val entered = CountDownLatch(1)
        device.writer = { _, _, _ -> entered.countDown(); await(device.stopped); 0 }
        val ended = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { _, n -> n }
            await(entered)
            output.stop()
            await(ended)
            assertFalse(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun startedObserverCanCancelWithoutCreatingAnOverlappingWorker() {
        val device = FakeDevice()
        val ended = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val rejection = AtomicReference<Throwable?>()
        lateinit var output: AudioOutput
        output = device.open { event ->
            if (event is AudioOutputEvent.Started) {
                output.stop()
                rejection.set(runCatching { output.start { _, n -> n } }.exceptionOrNull())
            }
            if (event is AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { _, n -> callbacks.incrementAndGet(); n }
            await(ended)
            assertTrue(rejection.get() is IllegalStateException)
            assertEquals(0, callbacks.get())
            assertEquals(1, device.starts.get())
        } finally { output.close() }
    }

    @Test
    fun callbackCloseIsTerminalAndDoesNotWaitForItself() {
        val device = FakeDevice()
        val closedFromCallback = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        output.start { _, n -> output.close(); closedFromCallback.countDown(); n }
        await(closedFromCallback)
        await(ended)
        assertFalse(output.isRunning)
        assertEquals(0, device.writes.get())
        assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
        output.close()
    }

    @Test
    fun rendererViolationsStopWithContainedFailureEvents() {
        for (returned in listOf(-1, Int.MIN_VALUE, 5)) {
            val device = FakeDevice()
            val failed = AtomicInteger()
            val ended = CountDownLatch(1)
            val output = device.open {
                if (it is AudioOutputEvent.Failed) { failed.incrementAndGet(); error("broken observer") }
                if (it is AudioOutputEvent.Stopped) ended.countDown()
            }
            try {
                output.start { _, _ -> returned }
                await(ended)
                assertFalse(output.isRunning)
                assertEquals(1, failed.get())
                assertEquals(0, device.writes.get())
            } finally { output.close() }
        }
    }

    @Test
    fun observerFailuresDoNotKillTheWorker() {
        val device = FakeDevice()
        val rendered = CountDownLatch(2)
        val output = device.open { error("broken observer") }
        try {
            output.start { _, _ -> rendered.countDown(); 0 }
            await(rendered)
            assertTrue(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun backendStartFailureCanBeRetried() {
        val device = FakeDevice()
        val failures = AtomicInteger()
        val rendered = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Failed) failures.incrementAndGet() }
        try {
            device.startFailure = true
            output.start { _, n -> n }
            assertFalse(output.isRunning)
            assertEquals(1, failures.get())
            device.startFailure = false
            output.start { _, n -> rendered.countDown(); n }
            await(rendered)
            assertEquals(2, device.starts.get())
        } finally { output.close() }
    }

    @Test
    fun shortWritesPreserveEverySampleAndZeroProgressIsBounded() {
        val device = FakeDevice()
        val accepted = mutableListOf<Byte>()
        val ended = CountDownLatch(1)
        var transfers = 0
        device.writer = { data, offset, _ ->
            synchronized(accepted) { repeat(4) { accepted += data[offset + it] } }
            if (++transfers == 4) ended.countDown()
            4
        }
        val output = device.open()
        val renders = AtomicInteger()
        try {
            // A close ends at once, with no fade, so the line takes only the first block.
            output.start { out, n ->
                if (renders.incrementAndGet() == 1) { for (i in out.indices) out[i] = (i + 1) / 16f; n }
                else { output.close(); n }
            }
            await(ended)
            output.stop()
            assertEquals(16, synchronized(accepted) { accepted.size })
            val expected = (1..8).flatMap { value ->
                val sample = (value / 16f * 32767f).toInt()
                listOf((sample and 255).toByte(), (sample shr 8).toByte())
            }
            assertEquals(expected, accepted)
        } finally { output.close() }
        val zero = FakeDevice()
        zero.writer = { _, _, _ -> 0 }
        val failed = CountDownLatch(1)
        val zeroOutput = zero.open { if (it is AudioOutputEvent.DeviceLost) failed.countDown() }
        try {
            zeroOutput.start { _, n -> n }
            await(failed)
            assertFalse(zeroOutput.isRunning)
            assertEquals(10, zero.writes.get())
        } finally { zeroOutput.close() }
    }

    @Test
    fun invalidBufferSizesFailBeforeOpeningAnyBackend() {
        for (frames in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), frames) }
        }
    }

    /**
     * Plays [blocks] blocks of 16 bytes on a line of 32 bytes, short where [short] says so, then
     * stops from the renderer. Returns every event with the number of writes made when it arrived.
     */
    private fun play(device: FakeDevice, output: AudioOutput, relay: Relay, blocks: Int, short: (Int) -> Boolean = { false }): List<Pair<AudioOutputEvent, Int>> {
        val renders = AtomicInteger()
        val ended = CountDownLatch(1)
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        relay.target = { event ->
            synchronized(events) { events += event to device.writes.get() }
            if (event is AudioOutputEvent.Stopped) ended.countDown()
        }
        startAfterUnwind(output) { _, frames ->
            val block = renders.incrementAndGet()
            if (block > blocks) { output.stop(); frames } else if (short(block)) frames - 1 else frames
        }
        await(ended)
        return synchronized(events) { events.toList() }
    }

    private class Relay { @Volatile var target: (AudioOutputEvent) -> Unit = {} }

    private fun lineOfTwoBlocks(): Pair<FakeDevice, Relay> = FakeDevice().apply { bufferSize = 32 } to Relay()

    @Test
    fun anEmptyLineBeforeAFullBlockIsOneDeviceUnderrunAfterTheWrite() {
        val (device, relay) = lineOfTwoBlocks()
        // The line is empty before writes 1, 2, 4 and 6. The first two fill an empty line at the start.
        device.free = { writes -> if (writes in setOf(0, 1, 3, 5)) 32 else 0 }
        val output = device.open { relay.target(it) }
        try {
            val events = play(device, output, relay, blocks = 7)
            assertEquals(
                listOf(AudioOutputEvent.DeviceUnderrun(1, 1) to 4, AudioOutputEvent.DeviceUnderrun(1, 2) to 6),
                events.filter { it.first is AudioOutputEvent.DeviceUnderrun },
            )
            assertTrue(events.none { it.first is AudioOutputEvent.Underrun }, "$events")
            // At most one look at the line for each block, and none before the line was full once.
            assertEquals(5, device.availableCalls.get())
        } finally { output.close() }
    }

    @Test
    fun aShortRenderOnAnEmptyLineSendsOnlyUnderrun() {
        val (device, relay) = lineOfTwoBlocks()
        device.free = { 32 }
        val output = device.open { relay.target(it) }
        try {
            val events = play(device, output, relay, blocks = 4) { it >= 3 }
            assertTrue(events.none { it.first is AudioOutputEvent.DeviceUnderrun }, "$events")
            assertEquals(2, events.count { it.first == AudioOutputEvent.Underrun(1) })
        } finally { output.close() }
    }

    @Test
    fun totalsContinueAcrossStopAndStartAndEachStreamSkipsItsOwnStart() {
        val (device, relay) = lineOfTwoBlocks()
        device.free = { 32 }
        val output = device.open { relay.target(it) }
        try {
            // Blocks 3 and 4 of each stream find the line empty. Blocks 1 and 2 fill it at the start.
            // The fade and its play-out also find the line empty, and they count nothing.
            val first = play(device, output, relay, blocks = 4).filter { it.first is AudioOutputEvent.DeviceUnderrun }
            val before = device.writes.get()
            val second = play(device, output, relay, blocks = 4).filter { it.first is AudioOutputEvent.DeviceUnderrun }
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)), first.map { it.first })
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(1, 3), AudioOutputEvent.DeviceUnderrun(1, 4)), second.map { it.first })
            assertEquals(listOf(before + 3, before + 4), second.map { it.second })
        } finally { output.close() }
    }

    @Test
    fun estimatedLatencyUsesTheBackendCapacity() {
        val device = FakeDevice()
        val output = device.open()
        try { assertEquals(Latency(1024, LatencyQuality.Estimated), output.latency) }
        finally { output.close() }
    }

    private val fade = StopFade(48_000, 2)

    /** The left channel of little-endian 16-bit stereo [bytes]. */
    private fun leftSamples(bytes: List<Byte>): List<Int> =
        bytes.chunked(4) { (it[0].toInt() and 0xff) or (it[1].toInt() shl 8) }

    /** The 16-bit value of fade frame [index] for a render of [level]. */
    private fun faded(level: Float, index: Int): Int = (level * fade.gain(index) * 32767f).toInt()

    /** Waits for [ended], then returns the events, which must end with one Stopped. */
    private fun stoppedOnce(events: List<AudioOutputEvent>, ended: CountDownLatch): List<AudioOutputEvent> {
        await(ended)
        Thread.sleep(20)
        val seen = synchronized(events) { events.toList() }
        assertEquals(1, seen.count { it == AudioOutputEvent.Stopped }, "$seen")
        assertEquals(AudioOutputEvent.Stopped, seen.last(), "$seen")
        assertTrue(seen.none { it is AudioOutputEvent.DeviceLost || it is AudioOutputEvent.Failed }, "$seen")
        return seen
    }

    @Test
    fun aStopFromTheRendererFadesAcrossBlocksThenPlaysOutSilence() {
        val device = FakeDevice()
        val taken = device.record()
        val events = mutableListOf<AudioOutputEvent>()
        val ended = CountDownLatch(1)
        val renders = AtomicInteger()
        val runningAfterStop = AtomicReference<Boolean?>()
        val restart = AtomicReference<Throwable?>()
        lateinit var output: AudioOutput
        output = device.open(bufferFrames = 100) {
            synchronized(events) { events += it }
            if (it == AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { out, frames ->
                out.fill(0.5f)
                if (renders.incrementAndGet() == 3) {
                    output.stop()
                    runningAfterStop.set(output.isRunning)
                    restart.set(runCatching { output.start { _, n -> n } }.exceptionOrNull())
                }
                frames
            }
            stoppedOnce(events, ended)
            assertEquals(false, runningAfterStop.get(), "isRunning was true after stop returned")
            assertIs<IllegalStateException>(restart.get(), "a start during the fade was not rejected")
            // 240 frames of fade span blocks 3, 4 and 5, and the callback is called for each of them.
            assertEquals(240, fade.frames)
            assertEquals(5, renders.get())
            val left = leftSamples(synchronized(taken) { taken.toList() })
            val full = (0.5f * 32767f).toInt()
            assertEquals(List(200) { full }, left.subList(0, 200))
            assertEquals(List(240) { faded(0.5f, it) }, left.subList(200, 440))
            assertEquals(0, left[439], "the fade does not end at zero")
            // The rest of block 5, then silence for the whole line: 1024 frames in blocks of 100.
            assertEquals(List(60 + 1100) { 0 }, left.subList(440, left.size))
        } finally { output.close() }
    }

    @Test
    fun aStopFromAnotherThreadFadesOutBeforeItReturns() {
        val device = FakeDevice()
        val taken = device.record()
        val events = mutableListOf<AudioOutputEvent>()
        val ended = CountDownLatch(1)
        val three = CountDownLatch(3)
        val output = device.open(bufferFrames = 100) {
            synchronized(events) { events += it }
            if (it == AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { out, frames -> out.fill(0.5f); three.countDown(); frames }
            await(three)
            output.stop()
            assertFalse(output.isRunning)
            stoppedOnce(events, ended)
            val left = leftSamples(synchronized(taken) { taken.toList() })
            val full = (0.5f * 32767f).toInt()
            val fadeStart = left.indexOfFirst { it != full }
            assertTrue(fadeStart > 0 && fadeStart % 100 == 0, "the fade does not start on a block: $fadeStart")
            assertEquals(List(240) { faded(0.5f, it) }, left.subList(fadeStart, fadeStart + 240))
            assertEquals(List(left.size - fadeStart - 240) { 0 }, left.subList(fadeStart + 240, left.size))
            assertTrue(left.size - fadeStart - 240 >= 1024, "the play-out is shorter than the line")
        } finally { output.close() }
    }

    @Test
    fun aWriteFailureDuringTheFadeSendsNoDeviceLost() {
        for (result in listOf(-1, 0)) {
            val device = FakeDevice()
            val fading = AtomicReference(false)
            device.writer = { _, _, count -> if (fading.get()) result else count }
            val events = mutableListOf<AudioOutputEvent>()
            val ended = CountDownLatch(1)
            val renders = AtomicInteger()
            lateinit var output: AudioOutput
            output = device.open(bufferFrames = 100) {
                synchronized(events) { events += it }
                if (it == AudioOutputEvent.Stopped) ended.countDown()
            }
            try {
                output.start { _, frames ->
                    if (renders.incrementAndGet() == 3) { fading.set(true); output.stop() }
                    frames
                }
                stoppedOnce(events, ended)
                assertEquals(3, renders.get(), "result $result")
            } finally { output.close() }
        }
    }

    @Test
    fun aCloseDuringTheFadeEndsAtOnce() {
        val device = FakeDevice()
        val taken = device.record()
        val events = mutableListOf<AudioOutputEvent>()
        val ended = CountDownLatch(1)
        val renders = AtomicInteger()
        lateinit var output: AudioOutput
        output = device.open(bufferFrames = 100) {
            synchronized(events) { events += it }
            if (it == AudioOutputEvent.Stopped) ended.countDown()
        }
        output.start { out, frames ->
            out.fill(0.5f)
            when (renders.incrementAndGet()) {
                3 -> output.stop()
                4 -> output.close()
            }
            frames
        }
        stoppedOnce(events, ended)
        assertEquals(4, renders.get())
        // Blocks 1 to 3, and no faded block 4 and no silence after the close.
        assertEquals(300 * 4, synchronized(taken) { taken.size })
        assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
    }

    @Test
    fun aLineThatStopsTakingSoundDuringTheFadeHaltsAfterTheBoundedWait() {
        val device = FakeDevice()
        val stall = AtomicReference(false)
        device.writer = { _, _, count -> if (stall.get()) { await(device.stopped); 0 } else { Thread.sleep(1); count } }
        val events = mutableListOf<AudioOutputEvent>()
        val ended = CountDownLatch(1)
        val three = CountDownLatch(3)
        val output = device.open(bufferFrames = 100) {
            synchronized(events) { events += it }
            if (it == AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { _, frames -> three.countDown(); frames }
            await(three)
            stall.set(true)
            val before = System.nanoTime()
            output.stop()
            val waitedMillis = (System.nanoTime() - before) / 1_000_000
            assertFalse(output.isRunning)
            assertTrue(waitedMillis in 450..2_000, "stop waited $waitedMillis ms")
            stoppedOnce(events, ended)
        } finally { output.close() }
    }
}
