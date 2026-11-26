@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueuePropertyListenerProc
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.kAudioQueueProperty_IsRunning
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioTimeStampSampleTimeValid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_sync
import platform.posix.usleep
import kotlin.time.TimeSource

/** Native allocation/dispatch is real; every AudioQueue operation is a fake with no hardware. */
class AppleAudioOutputRegressionTest {
    private class Driver : AppleAudioQueueDriver {
        private var queueStorage: ByteVar? = null
        private var queue: AudioQueueRef? = null
        private val buffers = mutableListOf<AudioQueueBufferRef>()
        private var callback: ((AudioQueueRef?, AudioQueueBufferRef?) -> Unit)? = null
        private var deliveryQueue: dispatch_queue_t = null
        val allocations = AtomicInt(0)
        val enqueues = AtomicInt(0)
        val starts = AtomicInt(0)
        val disposals = AtomicInt(0)
        val disposed = AtomicBoolean(false)
        val rejectDispose = AtomicBoolean(false)
        val rejectEnqueue = AtomicBoolean(false)
        var allocationFailure = -1
        val lifetimeViolation = AtomicBoolean(false)
        var disposalCheck: () -> Boolean = { true }
        val running = AtomicBoolean(false)
        val listenersAdded = AtomicInt(0)
        val listenersRemoved = AtomicInt(0)
        val wrongListenerRemoved = AtomicBoolean(false)
        private var listener: AudioQueuePropertyListenerProc? = null
        private var listenerData: COpaquePointer? = null
        // The clock of the queue. The test sets it before it fires a callback.
        var sampleTime = 0.0
        var clockFlags = kAudioTimeStampSampleTimeValid
        var clockStatus = 0
        val clockReads = AtomicInt(0)
        // Stands in for the AudioQueue thread that calls property listeners.
        private val systemQueue = dispatch_queue_create("fake-audio-queue-system", null)
        override fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
                            callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int {
            val storage = nativeHeap.alloc<ByteVar>()
            queueStorage = storage
            this.queue = storage.ptr.reinterpret()
            disposed.store(false)
            output.pointed.value = this.queue
            this.callback = callback
            deliveryQueue = queue
            return 0
        }
        override fun allocate(queue: AudioQueueRef, bytes: UInt, output: CPointer<CPointerVar<AudioQueueBuffer>>): Int {
            val index = allocations.fetchAndAdd(1)
            if (index == allocationFailure) return -1
            val buffer = nativeHeap.alloc<AudioQueueBuffer>()
            buffer.mAudioData = nativeHeap.allocArray<ByteVar>(bytes.toInt())
            buffer.mAudioDataBytesCapacity = bytes
            buffer.mAudioDataByteSize = 0u
            output.pointed.value = buffer.ptr
            buffers += buffer.ptr
            return 0
        }
        /** Runs once inside the next enqueue, which then refuses the buffer as a stopped queue does. */
        var enqueueHook: (() -> Unit)? = null
        /** The samples of every accepted buffer, in the order of the enqueues. */
        val enqueued = AtomicReference<List<List<Short>>>(emptyList())
        override fun enqueue(queue: AudioQueueRef, buffer: AudioQueueBufferRef): Int {
            if (disposed.load()) { lifetimeViolation.store(true); return -1 }
            enqueueHook?.let { hook ->
                enqueueHook = null
                hook()
                return KAUDIO_QUEUE_ERR_ENQUEUE_DURING_RESET
            }
            enqueues.fetchAndAdd(1)
            if (rejectEnqueue.load()) return -1
            val data = buffer.pointed.mAudioData!!.reinterpret<ShortVar>()
            val samples = List((buffer.pointed.mAudioDataByteSize / 2u).toInt()) { data[it] }
            enqueued.store(enqueued.load() + listOf(samples))
            return 0
        }
        // Like AudioQueue, a start and a stop each report the new running state to the listener.
        override fun start(queue: AudioQueueRef): Int { starts.fetchAndAdd(1); running.store(true); postRunningChange(); return 0 }
        override fun stop(queue: AudioQueueRef): Int { running.store(false); postRunningChange(); return 0 }
        override fun addRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int {
            dispatch_sync(systemQueue) { this.listener = listener; listenerData = userData }
            listenersAdded.fetchAndAdd(1)
            return 0
        }
        override fun removeRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int {
            dispatch_sync(systemQueue) {
                if (listener != this.listener || userData != listenerData) wrongListenerRemoved.store(true)
                this.listener = null
                listenerData = null
            }
            listenersRemoved.fetchAndAdd(1)
            return 0
        }
        override fun isRunning(queue: AudioQueueRef): Boolean? = running.load()
        override fun currentTime(queue: AudioQueueRef, stamp: CPointer<AudioTimeStamp>): Int {
            clockReads.fetchAndAdd(1)
            stamp.pointed.mSampleTime = sampleTime
            stamp.pointed.mFlags = clockFlags
            return clockStatus
        }
        override fun dispose(queue: AudioQueueRef): Int {
            // A synchronous AudioQueue disposal returns after its last listener call.
            dispatch_sync(systemQueue) { }
            disposals.fetchAndAdd(1)
            if (!disposalCheck()) lifetimeViolation.store(true)
            if (rejectDispose.load()) return -1
            buffers.forEach { buffer ->
                nativeHeap.free(buffer.pointed.mAudioData!!)
                nativeHeap.free(buffer)
            }
            buffers.clear()
            nativeHeap.free(queueStorage!!.ptr)
            queueStorage = null
            callback = null
            disposed.store(true)
            return 0
        }
        fun fireAsync() {
            val completion = callback!!
            val buffer = buffers.first()
            val currentQueue = queue
            dispatch_async(deliveryQueue) { completion(currentQueue, buffer) }
        }
        fun flushCallbacks() { dispatch_sync(deliveryQueue) { } }

        fun flushSystem() { dispatch_sync(systemQueue) { } }

        /** True once the output opened a queue, which also gives the fake its callback queue. */
        val created: Boolean get() = deliveryQueue != null

        /** The system stops the queue while it plays, as an interruption or a lost device does. */
        fun stopRunning() { running.store(false); postRunningChange() }

        /** A stop by the system that also hands a buffer back, which the stopped queue then refuses. */
        fun stopRunningAndReturnABuffer() {
            running.store(false)
            rejectEnqueue.store(true)
            fireAsync()
            flushCallbacks()
            postRunningChange()
        }

        private fun postRunningChange() {
            val currentQueue = queue
            dispatch_async(systemQueue) {
                val proc = listener ?: return@dispatch_async
                proc(listenerData, currentQueue, kAudioQueueProperty_IsRunning)
            }
        }
    }

    private companion object {
        // kAudioQueueErr_EnqueueDuringReset, which a real queue returned in this case.
        const val KAUDIO_QUEUE_ERR_ENQUEUE_DURING_RESET = -66632
    }

    /** Appends without a lock: the output's queues and the test thread all touch the list. */
    private class Events {
        private val list = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val all: List<AudioOutputEvent> get() = list.load()
        fun add(event: AudioOutputEvent) {
            while (true) {
                val current = list.load()
                if (list.compareAndSet(current, current + event)) return
            }
        }
        fun count(predicate: (AudioOutputEvent) -> Boolean): Int = all.count(predicate)
        val stops: Int get() = count { it == AudioOutputEvent.Stopped }
        val losses: List<AudioOutputEvent.DeviceLost> get() = all.filterIsInstance<AudioOutputEvent.DeviceLost>()
    }

    private val stereo = AudioFormat(48_000, ChannelLayout.STEREO)

    /** Waits until the output has finished its work and its Stopped observer has returned. */
    private fun settle(output: AppleAudioOutput, driver: Driver) {
        driver.flushSystem()
        output.awaitControlQueue()
        if (driver.created) driver.flushCallbacks()
    }

    private fun await(condition: () -> Boolean) {
        repeat(3000) { if (condition()) return; usleep(1_000u) }
        assertTrue(condition(), "expected asynchronous state was not reached")
    }

    @Test
    fun closeDuringPrimingDefersDisposalAndNeverUsesTheFreedBuffer() {
        val driver = Driver()
        val inside = AtomicBoolean(false)
        driver.disposalCheck = { !inside.load() }
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, {}, driver)
        output.start { _, frames ->
            inside.store(true)
            output.close()
            assertFalse(driver.disposed.load())
            inside.store(false)
            frames
        }
        await { driver.disposed.load() }
        assertFalse(output.isRunning)
        assertFalse(driver.lifetimeViolation.load())
        assertEquals(1, driver.allocations.load())
        assertEquals(0, driver.enqueues.load())
        assertEquals(0, driver.starts.load())
        assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
    }

    @Test
    fun allocationFailureAtEveryPositionNeverStartsAndReclaimsPartialResources() {
        for (position in 0..2) {
            val driver = Driver().apply { allocationFailure = position }
            val failures = AtomicInt(0)
            val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, { if (it is AudioOutputEvent.Failed) failures.fetchAndAdd(1) }, driver)
            try {
                output.start { _, frames -> frames }
                await { driver.disposed.load() }
                driver.flushCallbacks()
                assertFalse(output.isRunning)
                assertEquals(position + 1, driver.allocations.load())
                assertEquals(0, driver.starts.load())
                assertTrue(failures.load() > 0)
            } finally { output.close() }
        }
    }

    @Test
    fun enqueueFailuresDuringPrimingAndPlaybackStopTheGeneration() {
        for (duringPriming in listOf(true, false)) {
            val driver = Driver()
            val failures = AtomicInt(0)
            driver.rejectEnqueue.store(duringPriming)
            val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, { if (it is AudioOutputEvent.Failed) failures.fetchAndAdd(1) }, driver)
            try {
                output.start { _, frames -> frames }
                if (!duringPriming) {
                    assertTrue(output.isRunning)
                    driver.rejectEnqueue.store(true)
                    driver.fireAsync()
                }
                await { driver.disposed.load() }
                driver.flushCallbacks()
                assertFalse(output.isRunning)
                assertTrue(failures.load() > 0)
                assertEquals(if (duringPriming) 0 else 1, driver.starts.load())
            } finally { output.close() }
        }
    }

    @Test
    fun failedDisposalRetainsOwnershipAndCanBeRetried() {
        val driver = Driver()
        driver.rejectDispose.store(true)
        val failures = AtomicInt(0)
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, {
            if (it is AudioOutputEvent.Failed) failures.fetchAndAdd(1)
        }, driver)
        output.start { _, frames -> frames }
        output.stop()
        await { failures.load() > 0 }
        assertFalse(driver.disposed.load())
        assertFalse(output.isRunning)
        assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
        driver.rejectDispose.store(false)
        output.close()
        await { driver.disposed.load() }
        assertTrue(driver.disposals.load() >= 2)
    }

    @Test
    fun closingFromADisposalFailureObserverDoesNotCreateARetryLoop() {
        val driver = Driver()
        driver.rejectDispose.store(true)
        val failures = AtomicInt(0)
        lateinit var output: AudioOutput
        output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, {
            if (it is AudioOutputEvent.Failed) { failures.fetchAndAdd(1); output.close() }
        }, driver)
        output.start { _, frames -> frames }
        output.stop()
        await { driver.disposals.load() >= 2 }
        driver.flushCallbacks()
        usleep(20_000u)
        assertEquals(1, failures.load())
        assertEquals(2, driver.disposals.load())
        driver.rejectDispose.store(false)
        output.close()
        await { driver.disposed.load() }
    }

    @Test
    fun callbackCloseAndThrowingObserversCannotEscapeOrEnqueueAfterCancellation() {
        val driver = Driver()
        val calls = AtomicInt(0)
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, { error("broken observer") }, driver)
        output.start { _, frames -> if (calls.fetchAndAdd(1) >= 3) output.close(); frames }
        driver.fireAsync()
        await { driver.disposed.load() }
        assertEquals(3, driver.enqueues.load())
        assertFalse(output.isRunning)
    }

    @Test
    fun aStoppedGenerationCanBeReplacedWithoutRevivingItsRenderer() {
        val driver = Driver()
        val oldCalls = AtomicInt(0)
        val newCalls = AtomicInt(0)
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, {}, driver)
        output.start { _, frames -> oldCalls.fetchAndAdd(1); frames }
        output.stop()
        await { driver.disposed.load() }
        driver.flushCallbacks()
        output.start { _, frames -> newCalls.fetchAndAdd(1); frames }
        assertTrue(output.isRunning)
        driver.fireAsync()
        await { newCalls.load() >= 4 }
        output.close()
        await { driver.disposed.load() }
        assertEquals(3, oldCalls.load())
        assertEquals(2, driver.starts.load())
        assertFalse(driver.lifetimeViolation.load())
    }

    @Test
    fun aBlockedCallbackDoesNotMakeStopOrRejectedRestartWaitForTeardown() {
        val driver = Driver()
        val calls = AtomicInt(0)
        val entered = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4, {}, driver)
        output.start { _, frames ->
            if (calls.fetchAndAdd(1) >= 3) {
                entered.store(true)
                await { released.load() }
            }
            frames
        }
        driver.fireAsync()
        await { entered.load() }
        try {
            output.stop()
            assertFalse(output.isRunning)
            assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
            assertFalse(driver.disposed.load())
        } finally {
            released.store(true)
            driver.flushCallbacks()
            output.close()
        }
        await { driver.disposed.load() }
        // The block that was rendering when stop came is the first block of the fade.
        assertEquals(4, driver.enqueues.load())
    }
    /** Checks the end of a stream the system stopped: one DeviceLost, then one Stopped, and nothing after. */
    private fun assertOneLossThenStopped(events: Events, reason: String) {
        val seen = events.all
        assertEquals(1, events.losses.size, "DeviceLost count: $seen")
        assertEquals(1, events.stops, "Stopped count: $seen")
        assertEquals(reason, events.losses.single().reason)
        val lost = seen.indexOfFirst { it is AudioOutputEvent.DeviceLost }
        assertEquals(seen.size - 2, lost, "DeviceLost is not right before Stopped: $seen")
        assertEquals(AudioOutputEvent.Stopped, seen.last())
        assertTrue(seen.none { it is AudioOutputEvent.Failed }, "a loss reported Failed: $seen")
    }

    @Test
    fun aQueueTheSystemStopsEndsTheStreamWithDeviceLostThenStopped() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            // The start reported running to the listener, and that is not a loss.
            assertTrue(output.isRunning)
            assertTrue(events.losses.isEmpty())
            driver.stopRunning()
            await { events.stops == 1 }
            settle(output, driver)
            assertFalse(output.isRunning)
            assertOneLossThenStopped(events, "the system stopped the audio queue")
            assertTrue(driver.disposed.load())
            assertEquals(1, driver.listenersRemoved.load())
            assertFalse(driver.wrongListenerRemoved.load())
            assertEquals(0, output.openListenerRefs)
        } finally { output.close() }
    }

    @Test
    fun aBufferTheStoppedQueueRefusesIsPartOfTheLossNotAFailure() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            driver.stopRunningAndReturnABuffer()
            await { events.stops == 1 }
            settle(output, driver)
            assertFalse(output.isRunning)
            assertOneLossThenStopped(events, "the system stopped the audio queue")
        } finally { output.close() }
    }

    @Test
    fun startAfterTheSystemStoppedTheQueuePlaysAgain() {
        val driver = Driver()
        val events = Events()
        val calls = AtomicInt(0)
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            driver.stopRunning()
            await { events.stops == 1 }
            settle(output, driver)
            output.start { _, frames -> calls.fetchAndAdd(1); frames }
            assertTrue(output.isRunning)
            driver.fireAsync()
            await { calls.load() >= 4 }
            settle(output, driver)
            assertTrue(output.isRunning)
            assertEquals(2, driver.starts.load())
            assertEquals(2, driver.listenersAdded.load())
            assertEquals(1, events.losses.size)
        } finally { output.close() }
        await { driver.disposed.load() }
        assertFalse(driver.lifetimeViolation.load())
    }

    @Test
    fun aStopOrCloseByTheAppSendsNoDeviceLost() {
        for (close in listOf(false, true)) {
            val driver = Driver()
            val events = Events()
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            output.start { _, frames -> frames }
            settle(output, driver)
            if (close) output.close() else output.stop()
            await { events.stops == 1 }
            // The stop of the queue reaches no listener, and later notices find nothing to end.
            driver.stopRunning()
            SessionNotice.entries.forEach(output::onSessionNotice)
            settle(output, driver)
            assertFalse(output.isRunning)
            assertTrue(events.losses.isEmpty(), "close=$close: ${events.all}")
            assertEquals(1, events.stops)
            assertEquals(1, driver.listenersRemoved.load())
            assertEquals(0, output.openListenerRefs)
            output.close()
        }
    }

    @Test
    fun aNoticeWhileStoppedSendsNothing() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            // Never started.
            SessionNotice.entries.forEach(output::onSessionNotice)
            settle(output, driver)
            assertTrue(events.all.isEmpty(), "${events.all}")
            // Started, then stopped by the system once. Later notices change nothing.
            output.start { _, frames -> frames }
            driver.stopRunning()
            await { events.stops == 1 }
            driver.stopRunning()
            SessionNotice.entries.forEach(output::onSessionNotice)
            settle(output, driver)
            assertEquals(1, events.losses.size, "${events.all}")
            assertEquals(1, events.stops)
            assertFalse(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun eachSessionNoticeEndsAPlayingStreamOnceAndStartPlaysAgain() {
        for (notice in SessionNotice.entries) {
            val driver = Driver()
            val events = Events()
            val calls = AtomicInt(0)
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            try {
                output.start { _, frames -> frames }
                settle(output, driver)
                // The observers call from a system thread, and a notice can arrive twice.
                output.onSessionNotice(notice)
                output.onSessionNotice(notice)
                await { events.stops == 1 }
                settle(output, driver)
                assertFalse(output.isRunning, "$notice")
                assertOneLossThenStopped(events, notice.reason)
                assertTrue(driver.disposed.load())
                assertEquals(0, output.openListenerRefs)

                output.start { _, frames -> calls.fetchAndAdd(1); frames }
                assertTrue(output.isRunning, "$notice")
                driver.fireAsync()
                await { calls.load() >= 4 }
                assertEquals(2, driver.starts.load())
            } finally { output.close() }
            await { driver.disposed.load() }
        }
    }

    @Test
    fun aQueueThatCannotBeDisposedAfterAMediaServicesResetDoesNotBlockARestart() {
        for (notice in listOf(SessionNotice.MediaServicesLost, SessionNotice.MediaServicesReset)) {
            val driver = Driver()
            val events = Events()
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            try {
                output.start { _, frames -> frames }
                driver.rejectDispose.store(true)
                output.onSessionNotice(notice)
                await { events.stops == 1 }
                settle(output, driver)
                assertOneLossThenStopped(events, notice.reason)
                driver.rejectDispose.store(false)
                output.start { _, frames -> frames }
                assertTrue(output.isRunning, "$notice")
            } finally { output.close() }
        }
    }

    @Test
    fun aMediaServicesResetReleasesAQueueWhoseDisposalFailedEarlier() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            driver.rejectDispose.store(true)
            output.stop()
            await { events.count { it is AudioOutputEvent.Failed } == 1 }
            assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
            output.onSessionNotice(SessionNotice.MediaServicesReset)
            await { events.stops == 1 }
            settle(output, driver)
            // The app stopped this stream, so the reset only finishes the release.
            assertTrue(events.losses.isEmpty(), "${events.all}")
            driver.rejectDispose.store(false)
            output.start { _, frames -> frames }
            assertTrue(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun anInterruptionWhoseQueueCannotBeDisposedKeepsTheQueueAndReportsTheFailure() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            driver.rejectDispose.store(true)
            output.onSessionNotice(SessionNotice.InterruptionBegan)
            await { events.count { it is AudioOutputEvent.Failed } == 1 }
            settle(output, driver)
            assertEquals(1, events.losses.size)
            assertEquals(0, events.stops)
            assertFalse(output.isRunning)
            assertEquals(1, output.openListenerRefs)
            assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
            driver.rejectDispose.store(false)
        } finally { output.close() }
        await { driver.disposed.load() }
        settle(output, driver)
        assertEquals(1, events.losses.size)
        assertEquals(1, events.stops)
        assertEquals(0, output.openListenerRefs)
    }

    @Test
    fun theSessionIsWatchedOnlyWhileAStreamRuns() {
        val driver = Driver()
        val events = Events()
        val watches = AtomicInt(0)
        val unwatches = AtomicInt(0)
        val output = AppleAudioOutput(stereo, 4, events::add, driver) {
            watches.fetchAndAdd(1)
            val stopWatching: () -> Unit = { unwatches.fetchAndAdd(1) }
            stopWatching
        }
        assertEquals(0, watches.load())
        output.start { _, frames -> frames }
        assertEquals(1, watches.load())
        assertEquals(0, unwatches.load())
        output.onSessionNotice(SessionNotice.OldDeviceUnavailable)
        await { events.stops == 1 }
        settle(output, driver)
        assertEquals(1, unwatches.load())
        output.start { _, frames -> frames }
        assertEquals(2, watches.load())
        output.close()
        await { driver.disposed.load() }
        settle(output, driver)
        assertEquals(2, unwatches.load())
        assertEquals(driver.listenersAdded.load(), driver.listenersRemoved.load())
        assertEquals(0, output.openListenerRefs)
    }

    @Test
    fun aLossRacingAnAppStopEndsTheStreamOnce() {
        repeat(50) { round ->
            val driver = Driver()
            val events = Events()
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            output.start { _, frames -> frames }
            driver.stopRunning()
            output.onSessionNotice(SessionNotice.InterruptionBegan)
            output.stop()
            await { events.stops == 1 }
            settle(output, driver)
            assertEquals(1, events.stops, "round $round: ${events.all}")
            assertTrue(events.losses.size <= 1, "round $round: ${events.all}")
            if (events.losses.isNotEmpty()) assertIs<AudioOutputEvent.DeviceLost>(events.all[events.all.size - 2])
            assertFalse(output.isRunning)
            output.close()
        }
    }

    /** Sets the clock of the queue to [time], hands a buffer back and waits until the output has refilled it. */
    private fun block(driver: Driver, time: Double) {
        driver.sampleTime = time
        driver.fireAsync()
        driver.flushCallbacks()
    }

    private fun deviceUnderruns(events: Events) = events.all.filterIsInstance<AudioOutputEvent.DeviceUnderrun>()

    @Test
    fun aQueueClockPastTheEnqueuedFramesIsOneDeviceUnderrunAfterTheEnqueue() {
        val driver = Driver()
        val events = Events()
        val enqueuesAtEvents = AtomicReference<List<Int>>(emptyList())
        val output = AppleAudioOutput(stereo, 4, { event ->
            events.add(event)
            if (event is AudioOutputEvent.DeviceUnderrun) enqueuesAtEvents.store(enqueuesAtEvents.load() + driver.enqueues.load())
        }, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            assertEquals(0, driver.clockReads.load(), "the clock was read while the buffers were primed")
            // Priming enqueued 12 frames, and the clock has passed them: the queue ran dry.
            block(driver, 13.0)
            // 16 frames are enqueued now. A lead of less than one block is still the same dropout.
            block(driver, 13.0)
            // A lead of a whole block ends the dropout. The next pass is a new one.
            block(driver, 14.0)
            block(driver, 30.0)
            settle(output, driver)
            assertEquals(listOf(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)), deviceUnderruns(events))
            assertEquals(listOf(4, 7), enqueuesAtEvents.load(), "an event came before its enqueue")
            assertTrue(events.all.none { it is AudioOutputEvent.Underrun }, "${events.all}")
            assertEquals(4, driver.clockReads.load(), "not one clock read for each block")
        } finally { output.close() }
    }

    @Test
    fun aShortRenderWhileTheQueueRunsDrySendsOnlyUnderrun() {
        val driver = Driver()
        val events = Events()
        val renders = AtomicInt(0)
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            // The three priming renders are full. The fourth is short, and the queue has run dry.
            output.start { _, frames -> if (renders.fetchAndAdd(1) == 3) frames - 1 else frames }
            block(driver, 13.0)
            // The same dropout goes on, and it stays counted as the short render's.
            block(driver, 13.0)
            settle(output, driver)
            assertTrue(deviceUnderruns(events).isEmpty(), "${events.all}")
            assertEquals(1, events.count { it == AudioOutputEvent.Underrun(1) })
        } finally { output.close() }
    }

    @Test
    fun deviceUnderrunTotalsContinueAcrossStopAndStart() {
        val driver = Driver()
        val events = Events()
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            block(driver, 13.0)
            output.stop()
            await { events.stops == 1 }
            settle(output, driver)
            // A new queue starts its clock and its enqueued frames again.
            output.start { _, frames -> frames }
            block(driver, 13.0)
            settle(output, driver)
            assertEquals(listOf(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)), deviceUnderruns(events))
        } finally { output.close() }
        await { driver.disposed.load() }
        assertFalse(driver.lifetimeViolation.load())
    }

    @Test
    fun aClockThatDoesNotAnswerSendsNothing() {
        for (invalid in listOf(true, false)) {
            val driver = Driver()
            val events = Events()
            if (invalid) driver.clockFlags = 0u else driver.clockStatus = -1
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            try {
                output.start { _, frames -> frames }
                block(driver, 100.0)
                settle(output, driver)
                assertEquals(1, driver.clockReads.load())
                assertTrue(deviceUnderruns(events).isEmpty(), "${events.all}")
            } finally { output.close() }
        }
    }

    @Test
    fun aBufferRefusedAfterTheSystemEndedTheStreamSendsNoFailed() {
        val driver = Driver()
        val events = Events()
        lateinit var output: AppleAudioOutput
        output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            // Inside the enqueue, the system stops the queue and the control queue ends the stream.
            driver.enqueueHook = {
                driver.stopRunning()
                await { !output.isRunning }
            }
            driver.fireAsync()
            await { events.stops == 1 }
            settle(output, driver)
            assertOneLossThenStopped(events, "the system stopped the audio queue")
        } finally { output.close() }
    }

    @Test
    fun aBufferRefusedAfterTheAppStoppedTheStreamSendsNothing() {
        val driver = Driver()
        val events = Events()
        lateinit var output: AppleAudioOutput
        output = AppleAudioOutput(stereo, 4, events::add, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            // Inside the enqueue, the app stops the stream. The queue then refuses the buffer.
            driver.enqueueHook = {
                output.stop()
                await { !output.isRunning }
            }
            driver.fireAsync()
            await { events.stops == 1 }
            settle(output, driver)
            val seen = events.all
            assertTrue(seen.none { it is AudioOutputEvent.Failed || it is AudioOutputEvent.DeviceLost }, "$seen")
            assertEquals(1, events.stops, "$seen")
        } finally { output.close() }
    }

    @Test
    fun aDeviceLostObserverCanCloseTheOutput() {
        val driver = Driver()
        val events = Events()
        lateinit var output: AppleAudioOutput
        output = AppleAudioOutput(stereo, 4, { event ->
            events.add(event)
            if (event is AudioOutputEvent.DeviceLost) {
                // A restart from inside the event is rejected; the app restarts after Stopped.
                assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
                output.close()
            }
        }, driver)
        output.start { _, frames -> frames }
        driver.stopRunning()
        await { events.stops == 1 }
        settle(output, driver)
        assertOneLossThenStopped(events, "the system stopped the audio queue")
        assertTrue(driver.disposed.load())
        assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
    }

    private val fade = StopFade(48_000, 2)

    /** The left channel of the enqueued buffers from [from] on, as one list. */
    private fun left(driver: Driver, from: Int, count: Int): List<Int> =
        driver.enqueued.load().subList(from, from + count).flatten().filterIndexed { i, _ -> i % 2 == 0 }.map { it.toInt() }

    /** Fires [count] callbacks, one after the other. */
    private fun fire(driver: Driver, count: Int) = repeat(count) { driver.fireAsync(); driver.flushCallbacks() }

    @Test
    fun aStopFromTheRendererFadesAcrossBuffersThenEnqueuesSilenceUntilTheFadedBufferIsBack() {
        val driver = Driver()
        val events = Events()
        val renders = AtomicInt(0)
        val runningAfterStop = AtomicReference<Boolean?>(null)
        val restart = AtomicReference<Throwable?>(null)
        lateinit var output: AppleAudioOutput
        output = AppleAudioOutput(stereo, 100, events::add, driver)
        try {
            output.start { out, frames ->
                out.fill(0.5f)
                if (renders.addAndFetch(1) == 5) {
                    output.stop()
                    runningAfterStop.store(output.isRunning)
                    restart.store(runCatching { output.start { _, n -> n } }.exceptionOrNull())
                }
                frames
            }
            settle(output, driver)
            val clockReads = driver.clockReads.load()
            // Render 4 plays, render 5 stops, and renders 5 to 7 hold the 240 frames of the fade.
            fire(driver, 4)
            assertEquals(false, runningAfterStop.load(), "isRunning was true after stop returned")
            assertIs<IllegalStateException>(restart.load(), "a start during the fade was not rejected")
            assertEquals(7, renders.load())
            assertEquals(clockReads + 1, driver.clockReads.load(), "the fade read the queue clock")
            val full = (0.5f * 32767f).toInt()
            assertEquals(List(100) { full }, left(driver, 3, 1))
            val faded = left(driver, 4, 3)
            assertEquals(List(240) { (0.5f * fade.gain(it) * 32767f).toInt() }, faded.subList(0, 240))
            assertEquals(0, faded[239], "the fade does not end at zero")
            assertEquals(List(60) { 0 }, faded.subList(240, 300))

            // The next two callbacks enqueue silence. The third hands back the buffer with the end of the fade.
            fire(driver, 2)
            settle(output, driver)
            assertEquals(0, events.stops, "stopped before the faded buffer played: ${events.all}")
            assertEquals(List(200) { 0 }, left(driver, 7, 2))
            fire(driver, 1)
            await { events.stops == 1 }
            settle(output, driver)
            assertEquals(9, driver.enqueues.load())
            assertEquals(7, renders.load())
            assertTrue(driver.disposed.load())
            assertEquals(AudioOutputEvent.Stopped, events.all.last())
            assertTrue(events.all.none { it is AudioOutputEvent.Failed || it is AudioOutputEvent.DeviceLost }, "${events.all}")
        } finally { output.close() }
    }

    @Test
    fun aSystemEndDuringTheFadeSendsNoDeviceLost() {
        val ends = listOf<Pair<String, (Driver, AppleAudioOutput) -> Unit>>(
            "queue stop" to { driver, _ -> driver.stopRunning() },
            "refused buffer" to { driver, _ -> driver.stopRunningAndReturnABuffer() },
        ) + SessionNotice.entries.map { notice -> notice.name to { _: Driver, output: AppleAudioOutput -> output.onSessionNotice(notice) } }
        for ((name, end) in ends) {
            val driver = Driver()
            val events = Events()
            val output = AppleAudioOutput(stereo, 4, events::add, driver)
            try {
                output.start { _, frames -> frames }
                settle(output, driver)
                output.stop()
                end(driver, output)
                await { events.stops == 1 }
                settle(output, driver)
                // The fallback of the stop finds nothing left to release.
                usleep(150_000u)
                settle(output, driver)
                assertTrue(events.losses.isEmpty(), "$name: ${events.all}")
                assertTrue(events.all.none { it is AudioOutputEvent.Failed }, "$name: ${events.all}")
                assertEquals(1, events.stops, "$name: ${events.all}")
                assertFalse(output.isRunning)
                assertTrue(driver.disposed.load(), name)
            } finally { output.close() }
        }
    }

    @Test
    fun aCloseDuringTheFadeEndsAtOnce() {
        val driver = Driver()
        val events = Events()
        val renders = AtomicInt(0)
        val output = AppleAudioOutput(stereo, 4, events::add, driver)
        output.start { _, frames -> renders.fetchAndAdd(1); frames }
        settle(output, driver)
        output.stop()
        fire(driver, 1)
        val before = TimeSource.Monotonic.markNow()
        output.close()
        settle(output, driver)
        assertEquals(1, events.stops, "${events.all}")
        assertTrue(before.elapsedNow().inWholeMilliseconds < 100, "close waited for the fallback")
        assertTrue(driver.disposed.load())
        assertEquals(4, renders.load())
        assertFailsWith<IllegalStateException> { output.start { _, frames -> frames } }
    }

    @Test
    fun theFallbackReleasesAFadeWhoseQueueStopsAskingForBuffers() {
        val driver = Driver()
        val events = Events()
        // A latency of 3 buffers of 100 frames and a fade of 240 frames give a wait of 122.5 ms.
        val output = AppleAudioOutput(stereo, 100, events::add, driver)
        try {
            output.start { _, frames -> frames }
            settle(output, driver)
            val before = TimeSource.Monotonic.markNow()
            output.stop()
            assertFalse(output.isRunning)
            usleep(60_000u)
            settle(output, driver)
            assertEquals(0, events.stops, "released before the fallback: ${events.all}")
            await { events.stops == 1 }
            val waited = before.elapsedNow().inWholeMilliseconds
            assertTrue(waited in 110..2_000, "the fallback came after $waited ms")
            settle(output, driver)
            assertTrue(driver.disposed.load())
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.Stopped), events.all.filter { it !is AudioOutputEvent.Started })
            // Restart works once the generation has unwound.
            output.start { _, frames -> frames }
            assertTrue(output.isRunning)
        } finally { output.close() }
    }
}
