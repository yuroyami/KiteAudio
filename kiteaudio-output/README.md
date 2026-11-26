# kiteaudio-output

`kiteaudio-output` is the audio output module of [KiteAudio](../README.md). Your code fills
interleaved floating-point samples in `commonMain`, and the module sends them to the audio
backend of the platform.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
and review of its author.</sub>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](../LICENSE)

## What you get

`kiteaudio-output` provides one small output API backed by Java Sound on the JVM, `AudioTrack` on
Android, AudioQueue on Apple, and Web Audio in the browser. Your renderer supplies
mono or stereo `FloatArray` samples; the native backends clamp them and convert them to signed
16-bit PCM, and the browser plays them as Float32.

| Part | What it does |
| --- | --- |
| `openAudioOutput` | Opens an output on the default device or on a chosen device, with an `AudioFormatPolicy`. |
| `AudioOutput` | Starts, stops and releases a platform output stream. `format` is the format that the render callback produces. `deviceFormat` is the format that the hardware runs at, or null when the platform does not say. |
| `audioOutputDevices()` and `AudioOutputDevice` | List the places where the sound can go, with an id, a name, the default flag, and the rates and channel counts where the platform gives them. |
| `AudioFormatPolicy` | `Exact` fails the open when the platform rejects the format. `Nearest` opens the closest format that the platform accepts. |
| `AudioRenderCallback` | Fills the requested frames; a short render is padded with silence. |
| `AudioFormat` and `ChannelLayout` | Describe the sample rate and the channel layout. Both come from `kiteaudio-core`. An output plays one or two channels. |
| `Latency` and `LatencyQuality` | Report a delay value and distinguish estimates from measurements. All current backends report estimates. |
| `AudioOutputEvent` | Reports startup, stops, short renders, slow renders, a device that ran out of sound although every render was full (`DeviceUnderrun`), failures, and a stream that the system ended (`DeviceLost`). What can end a stream differs by platform. The rule after a loss is the same everywhere. |

`kiteaudio-core` is the KiteAudio module that holds the audio format types that several KiteAudio modules share.
`kiteaudio-output` depends on it and exposes it, so you do not add it yourself.

There is no Compose dependency, UI, audio decoder, synthesizer or input stream. Pair it
with [`kiteaudio-synth`](../kiteaudio-synth/README.md) to generate sound,
[`kiteaudio-midi`](../kiteaudio-midi/README.md) to read MIDI messages, and [`kiteaudio-codec`](../README.md#the-codec)
to decode audio files.

> [!NOTE]
> The version is `0.3.0`. The API can still change. 0.3.0 is not published to Maven Central yet,
> so build it from a source checkout.

## Install from source

`kiteaudio-output` builds with the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain), the
`./kotlin` script in the repository root. Clone this repository, then publish it to your local
Maven repository:

```sh
./kotlin publish mavenLocal -m kiteaudio-output
```

The publish signs the artifacts, so it needs a signing key in the `KOTLIN_TOOLCHAIN_SIGNING_KEY`
and `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` environment variables. Add `mavenLocal()` to the
repositories of your application, then add the dependency in a Kotlin Multiplatform application:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteaudio-output:0.3.0")
        }
    }
}
```

For a plain JVM or Android project, use the same dependency in your usual `dependencies { }`
block. With the Kotlin Toolchain, add it to `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-output:0.3.0
```

## A quick tour

### Render sound

This renderer makes a quiet mono sine wave and writes it to every configured channel.
Create and start it from your application's lifecycle code, and keep the returned output
until playback ends.

```kotlin
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import io.github.yuroyami.kiteaudio.output.AudioOutput
import io.github.yuroyami.kiteaudio.output.openAudioOutput
import kotlin.math.PI
import kotlin.math.sin

fun startTone(): AudioOutput {
    val output = openAudioOutput(
        format = AudioFormat(48_000, ChannelLayout.STEREO),
        bufferFrames = 512,
    )
    val format = output.format
    val step = 2.0 * PI * 440.0 / format.sampleRate
    var phase = 0.0

    try {
        output.start { out, frames ->
            for (frame in 0 until frames) {
                val value = (sin(phase) * 0.03).toFloat()
                for (channel in 0 until format.channels) {
                    out[frame * format.channels + channel] = value
                }
                phase += step
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
            frames
        }
    } catch (failure: Exception) {
        output.close()
        throw failure
    }
    return output
}

val output = startTone()
// Later, from the application's control/lifecycle thread:
output.stop()   // fades out. A close() before Stopped arrives ends the fade early.
output.close()
```

For mono, pass `AudioFormat(48_000, ChannelLayout.MONO)`. The output reads only the channel
count of the layout and ignores the speaker positions. A layout of more than two channels throws
`IllegalArgumentException` before any device access. The callback receives
`bufferFrames * output.format.channels` samples. Fill samples in channel order for each
frame and return a count in `0..frames`; returning `0` asks for silence. The array is
reused, so do not retain it or pass it to another thread.

### Choose a device and a format

`audioOutputDevices()` lists the devices. Pass one to `openAudioOutput` to play on it, or pass
no device to play on the default one. The list function is a `suspend` function, because a
browser answers it later.

```kotlin
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import io.github.yuroyami.kiteaudio.output.AudioFormatPolicy
import io.github.yuroyami.kiteaudio.output.AudioOutput
import io.github.yuroyami.kiteaudio.output.audioOutputDevices
import io.github.yuroyami.kiteaudio.output.openAudioOutput

suspend fun openOnUsb(): AudioOutput {
    val device = audioOutputDevices().firstOrNull { "USB" in it.name }
    val output = openAudioOutput(
        format = AudioFormat(48_000, ChannelLayout.STEREO),
        device = device,  // null plays on the default device
        formatPolicy = AudioFormatPolicy.Nearest,
    )
    // With Nearest, the output can open another rate or channel count. Render in this format.
    println("render at ${output.format}, the hardware runs at ${output.deviceFormat}")
    return output
}
```

`AudioFormatPolicy.Exact` is the default. The open fails when the platform rejects the format,
as it does without a policy. `Nearest` tries the requested format first. Then it keeps the
channel count and tries the sample rates by their distance from the requested rate, the higher
rate first on a tie. Then it tries the other channel count, mono or stereo, with the same rates.
The rates are the ones that the device reports, or 48000, 44100, 96000, 88200, 32000, 22050,
16000 and 8000 Hz when it reports none. When no format opens, the open fails as with `Exact`.
`AudioOutput.format` and the `Started` event hold the format that opened. A render at the
requested rate on an output that opened another rate plays at the wrong pitch, so read the format.

A layout of more than two channels still throws `IllegalArgumentException` before any device
access, with either policy. A device that the platform no longer knows fails the open in the
same way as a rejected format, with a reason that names the device. The output never falls back
to the default device.

| Platform | What the list holds | Can an open choose a device | `deviceFormat` |
| --- | --- | --- | --- |
| JVM | Every Java Sound mixer that offers a `SourceDataLine`. The id is the mixer name, with ` #2`, ` #3` and so on added when names repeat. The default is the first mixer that can mix, which `AudioSystem.getLine` picks when no system property names one. Rates and channel counts come from the signed 16-bit line formats where Java Sound gives a number. | Yes, through `Mixer.getLine`. An unknown id throws `LineUnavailableException`. | Null. Java Sound does not say. |
| Android | `audioOutputDevices()` returns one entry with the id `"system-route"`, the route that Android chooses. `audioOutputDevices(context)` returns that entry first, then every device of `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`, by the id of its `AudioDeviceInfo`. | Yes, through `AudioTrack.setPreferredDevice`. The device must be in the last list of `audioOutputDevices(context)`; any other id throws `UnsupportedOperationException`. | `AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)` with the channel count of the routed device, when that device reports exactly one count. Null otherwise. |
| macOS | Every CoreAudio device with an output stream. The id is the device UID. | Yes, through `kAudioQueueProperty_CurrentDevice`. An unknown UID throws `UnsupportedOperationException`. | The nominal rate and the output channel count of the device, from the CoreAudio hardware API. |
| iOS | One entry for the current route of `AVAudioSession`. The id joins the UIDs of the route's outputs. | The system chooses the route. An open with the entry checks that it is still the current route and throws `UnsupportedOperationException` when it is not. The output never changes the session. | `AVAudioSession.sampleRate` and `outputNumberOfChannels`. |
| Browser | The `audiooutput` entries of `navigator.mediaDevices.enumerateDevices()`. The first one is the default. The names are empty until the page has a media permission, for example from `getUserMedia`, and a browser may list fewer outputs or none before then. Node.js and a page that is not a secure context have no `navigator.mediaDevices`, so the list is empty there. | The default entry opens in every browser. Another entry passes its id as `sinkId` to the `AudioContext`, which Chromium 110 and newer accept. Elsewhere the open throws `UnsupportedOperationException`. The device must be in the last list; any other id throws `IllegalArgumentException`. | Null. A context at a requested rate hides the rate of the device. |

Java Sound, `AudioTrack`, AudioQueue and Web Audio can resample, so `Nearest` often opens the
requested format unchanged. On Apple platforms AudioQueue checks
a format only when it opens a queue. `Exact` keeps that check at `start()`, which sends `Failed`.
`Nearest` opens a queue for each format that it tries, then closes it again.

In a browser, an `AudioContext` with a `sinkId` that stops working fires an `error` event from
Chromium 128. The output then ends a playing stream with `DeviceLost`, and a later `start()` sends `Failed`.
Chromium 110 to 127 have no such event, so the output cannot tell.

### Inspect timing and events

```kotlin
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import io.github.yuroyami.kiteaudio.output.AudioOutputEvent
import io.github.yuroyami.kiteaudio.output.openAudioOutput

val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512) { event ->
    when (event) {
        is AudioOutputEvent.Started -> Unit   // format and latency are available here
        is AudioOutputEvent.Underrun -> Unit  // event.frames were replaced with silence
        is AudioOutputEvent.DeviceUnderrun -> Unit  // event.count new dropouts, event.total since open
        is AudioOutputEvent.Overrun -> Unit   // the render took event.tookMicros, past event.deadlineMicros
        is AudioOutputEvent.DeviceLost -> Unit
        is AudioOutputEvent.Failed -> Unit
        AudioOutputEvent.Stopped -> Unit
    }
}

val estimatedMicros = output.format.framesToMicros(output.latency.frames)
val quality = output.latency.quality
output.close()
```

An `Underrun` event means the renderer returned fewer frames, and the output played silence for the
rest of the block. An `Overrun` event means a render took longer than the block it filled lasts, even
when it returned every frame. A `DeviceUnderrun` event means the device ran out of sound although the
renderer filled every block, for example after a scheduling delay. Its unit is one dropout, not
frames. `count` gives the new dropouts and `total` gives all dropouts since the output was opened,
across stops and starts. The output sends `DeviceUnderrun` after it writes a block, at most once for
each block, and never from inside the render callback. A block with a short render sends only
`Underrun`, and the output counts no device underrun for that block.

An invalid frame count or render exception stops the stream and emits `Failed`. Event-handler
exceptions are contained so they cannot escape a worker or native callback.

Each platform gets the `DeviceUnderrun` count from a different source. Where the system keeps a
counter, the count is a measurement. Elsewhere the output estimates it.

| Platform | Source of the count | Measured or estimated |
| --- | --- | --- |
| JVM | The line has no bytes left to play right before a write: `SourceDataLine.available()` reports the whole buffer as free. The output checks this only after the stream has filled the line once, because every stream starts with an empty line. | Estimated. Java Sound has no counter. |
| Android 7.0 (API level 24) and newer | `AudioTrack.getUnderrunCount()`, read after each written block. The first block of each stream only sets the starting value. | Measured by the system. |
| Android 6 (API level 23) | None. The output sends no `DeviceUnderrun`. | |
| iOS and macOS | The clock of the AudioQueue (`AudioQueueGetCurrentTime`) has passed the end of every frame enqueued so far. The clock keeps running while the queue has no buffer. | Estimated. The clock runs behind the device by its output latency, so a dropout shorter than about that latency can go unseen. |
| Browser, worklet | The worklet played silence, and every render since it last handed a block back was full. Silence after a short render goes out as `Underrun`, in frames. | Estimated. The worklet is part of the output, not a counter of the browser. |
| Browser, scheduled buffers | The page timer fired after the booked blocks had run out, by the clock of the audio context. | Estimated. |

## Platforms

| Target | Backend | Integration requirements and current coverage |
| --- | --- | --- |
| JVM | `javax.sound.sampled.SourceDataLine` | JDK 21 toolchain; an installed Java Sound output provider supporting signed 16-bit PCM. The repository includes live audio tests. Linux and Windows use this JVM backend; there are no native Linux or Windows targets. |
| Android | Streaming `AudioTrack` | minSdk 23, compileSdk 37.2. Requests low-latency performance mode on API 26 and above; the system may choose a different performance path. The repository includes host tests that drive the output with a fake track; they are not a device test. |
| iOS arm64 | AudioQueue | `.github/scripts/apple_frameworks.py` links a dynamic framework named `KiteAudioOutput`. The host app owns its audio session: its category, its activation and its background-audio policy. The output never changes them. An interruption that begins, a route change that removes the output device, or a loss or reset of the media services ends the stream with `DeviceLost`. No iOS test sources are included. The macOS tests cover the shared logic with a fake queue, not the iOS observers. |
| iOS simulator arm64 and x64 | AudioQueue | The script links the same framework for both. Simulator support does not establish physical-device behavior. |
| macOS arm64 | AudioQueue | Uses the system AudioToolbox platform bindings. The repository includes live audio tests. A queue that stops running ends the stream with `DeviceLost`. A move to a new default device that keeps the queue running sends nothing. |
| JS and wasmJs in a browser | An `AudioWorkletNode` fed from the page, or `AudioBuffer`s scheduled by a page timer | `openAudioOutput` uses the worklet where the page can, and falls back to scheduled buffers in a page that is not a secure context or whose content security policy blocks a `blob:` worklet module. `Started` arrives once the browser lets the page play sound, usually after a click or a key press; a `start()` made before then waits, and calling `start()` again from the click or key handler asks the browser once more. Node.js has no Web Audio: opening an output throws `UnsupportedOperationException` there. Checked by hand with [verification/web](verification/web/README.md) in Chrome 156, Safari 27.2 and Firefox 159 on macOS; not yet on iOS. |

macOS x64, tvOS, watchOS, native Linux and Windows, and wasmWasi are not configured.
`audioOutputAvailable()` checks on the JVM that a Java Sound mixer offers an output line for signed 16-bit PCM;
on Android and Apple it always returns `true`; on the web it says whether the host has Web Audio. It does not prove that a stream can open,
that a route is available, or that sound reaches a speaker.

Apple uses Kotlin/Native's system AudioToolbox bindings and a block callback on a private serial
dispatch queue. A separate control queue owns setup and teardown; callback ownership survives
until native disposal succeeds. A listener for the running state of the queue, and on iOS the
`AVAudioSession` observers, hand each notice to the control queue, which ends the stream.
There is no custom cinterop definition or bundled C source.
The shared heap does not synchronize application renderer state: UI, MIDI and audio work still
need an explicit handoff.

## Resource and thread contracts

- Allocate renderer state before `start()`. Keep the render path bounded: no file access,
  logging, waits or locks. Reuse buffers and keep allocation out of the callback.
- JVM and Android render on a dedicated writer thread. Apple primes and subsequently renders
  on one serial callback queue; dispatch may use different OS threads over time. The browser
  renders on the page's main thread, so a long task elsewhere on the page can cause a `DeviceUnderrun`.
  With the worklet it renders a block each time the worklet hands one back; with scheduled
  buffers a timer keeps four blocks booked ahead. Browsers slow timers in a hidden tab, which
  should play as gaps with scheduled buffers; that has not been checked yet. Four blocks are in
  flight either way, as on the JVM.
- Event handlers must be fast. Transfer UI work to the UI thread outside the callback.
  `stop()` and `close()` are safe from a render or event callback; they never wait for themselves.
- `stop()` fades the sound out, then ends the current generation. `isRunning` is false when
  `stop()` returns. The render callback is still called for the blocks of the fade. The fade
  multiplies the sound by a gain that falls from 1 to 0 over 5 ms. The output then plays out
  the sound that is still queued, halts and sends `Stopped` once. A stream that has not
  started yet ends at once.
- The fade is bounded. JVM and Android wait up to 500 ms for it, then halt at once. Apple and
  the browser halt at once when the device stops asking for sound, after about twice the
  latency plus 100 ms. A system end during the fade sends no `DeviceLost`. A render failure
  during the fade ends it at once with `Failed`.
- In-progress work may finish after `stop()` returns. Restart is rejected until the fade,
  rendering and terminal event delivery have unwound, including the `Stopped` observer itself.
- `close()` makes the output permanently closed and releases platform output at once, with no
  fade. A `close()` during the fade of a `stop()` also ends it at once. To keep the fade,
  call `close()` after `Stopped` arrives. On the JVM and Android, `stop()` usually returns
  after `Stopped`. Always call `close()`, including after startup failure. A failed native disposal retains callback ownership and
  blocks restart; a later `stop()` or `close()` retries disposal.
- Use a positive, modest `bufferFrames` value. Bounds and multiplication are validated before
  allocation; return a frame count in `0..frames`. Partial writes are completed before the
  next block is rendered, and repeated zero-progress writes stop with `DeviceLost`.
- When the system ends a stream, the output sends `DeviceLost` and then `Stopped`, each once, and
  `isRunning` turns false. This rule is the same on every platform. The output does not resume
  by itself: call `start()` again after `Stopped` to play. A `stop()` or `close()` by the app
  sends no `DeviceLost`. These events end a stream:
  - JVM and Android: writes that make no progress, or a write that returns an invalid count.
  - iOS: an audio session interruption that begins, for example a phone call, Siri or an alarm.
    A route change that removes the output device, for example unplugged headphones. A loss or
    a reset of the system media services. After a reset, the next `start()` opens a new queue.
  - macOS: an AudioQueue that stops running. A move to a new default device that keeps the
    queue running is not a loss, and the sound goes on.
  - Browser: a context that closes, or that the browser suspends or interrupts once the stream
    has started, for a call or another app taking the audio. Start it again from a click or a
    key press, since the browser may want one before it resumes.

## Good to know

| Topic | Current limit |
| --- | --- |
| Real-time guarantees | JVM thread priority and Android's audio priority are scheduling requests. Kotlin/Native garbage collection and native callback execution also affect deadlines. No backend promises a hard deadline. |
| Latency | JVM and Android report the platform buffer capacity; Apple reports its three-buffer capacity; the browser reports its four blocks plus the context's `baseLatency`, whichever technique it uses. All are `Estimated`: capacity is not current queue occupancy or measured acoustic delay and excludes other device/route delays. |
| Formats | Mono and stereo only. A layout of more channels throws `IllegalArgumentException`. There is no float-device output or resampler. `AudioFormatPolicy.Nearest` picks a format that the platform accepts, but the app must render in it. With `Exact`, unsupported sample rates can fail to open. |
| Devices | Each platform lists and chooses devices differently; see [Choose a device and a format](#choose-a-device-and-a-format). iOS cannot choose a route. A browser without `sinkId` support can open only the default entry. Device names in a browser are empty until the page has a media permission. A list is a snapshot: the output does not report devices that come or go. |
| Routes and recovery | No automatic recovery, audio-focus integration or audio-session management. After `DeviceLost`, the app calls `start()` again. Native creation, allocation, enqueue, start, stop and disposal statuses are checked. The host app still manages its platform lifecycle. |
| Precision | Native backends clamp float samples to `[-1, 1]` and quantize them to 16-bit PCM without dithering. The browser passes Float32 through unchanged. |
| Apple latency | AudioQueue uses three queued buffers. A render-callback AudioUnit backend is not implemented. |
| Browser techniques | The worklet counts every frame of silence it plays; scheduled buffers estimate a `DeviceUnderrun` from the context clock, and allocate an `AudioBuffer` and a source node for every block. Nothing says which technique an output settled on. |
| Input | Recording and full-duplex streams are not implemented. |

See the [issue tracker](https://github.com/yuroyami/KiteAudio/issues) for defects and proposed
improvements. This README describes the current implementation; it does not certify audio
quality, route recovery or device coverage.

Java callers see declared checked exceptions on the `@Throws` entry points and may need to
catch or declare them. Kotlin callers keep the ordinary exception rules of Kotlin.

## Build and test

Use the `./kotlin` script in the repository root (`kotlin.bat` on Windows). It downloads the
JDK and Kotlin/Native that it needs. Android compilation needs an Android SDK with API level 37.2.
Apple compilation and linking need macOS and an appropriate Xcode SDK.

```sh
./kotlin build -m kiteaudio-output -p jvm -p android
./kotlin build -m kiteaudio-output -p macosArm64 -p iosSimulatorArm64
```

The Kotlin Toolchain does not link Apple frameworks. After a build of an iOS platform,
`python3 .github/scripts/apple_frameworks.py --platforms iosSimulatorArm64 --build-type debug --no-xcframework`
links `KiteAudioOutput.framework` from the klibs.

Deterministic lifecycle and transfer regressions use fake JVM/native drivers without opening audio:

```sh
./kotlin test -m kiteaudio-output -p jvm --include-classes='*RegressionTest'
./kotlin test -m kiteaudio-output -p macosArm64 --include-classes='*RegressionTest'
./kotlin test -m kiteaudio-output -p android --include-classes='*RegressionTest'
```

The Android tests run on the host JVM and need the Android SDK in `ANDROID_HOME`. They drive the
production Android output with a fake track, so they do not open a device or check real playback.
The unfiltered JVM and macOS suites also include live tests that open output and play a quiet tone:

```sh
./kotlin test -m kiteaudio-output -p jvm
./kotlin test -m kiteaudio-output -p macosArm64
```

The browser output's lifecycle runs against a stand-in for Web Audio on every platform, and its
worklet processor runs in Node:

```sh
python3 .github/scripts/web-tests/run.py --module kiteaudio-output
```

Playback in a real browser is a manual check; [verification/web](verification/web/README.md) says how.

Two small apps check on a real phone what the host tests cannot. [verification/android](verification/android/README.md)
forces dropouts and compares the `DeviceUnderrun` events with the counter of `AudioTrack`.
[verification/ios](verification/ios/README.md) checks that an interruption and a lost route end the stream with
`DeviceLost` and `Stopped`, what a suspension of the app does, and that `start()` plays again. Each README lists the
steps for the person who holds the phone.

JVM audio tests report themselves as skipped when no Java Sound mixer offers an output line for
signed 16-bit PCM; a skip is not proof of successful playback. macOS audio tests require a working
output. Neither suite establishes Android or physical iOS behavior. Fake-driver results establish
tested control flow, not hardware playback.

The workflow `.github/workflows/instruments.yml` runs these checks in CI. It runs the JVM tests,
the public API checks, the Android build and the Android host tests. It also runs the JS and
wasmJs tests in Node, and the macOS tests on a runner with a virtual output device. No CI job
plays sound on a physical device or in a real browser.

Known defects, open proposals and platform limits are tracked as
[GitHub issues](https://github.com/yuroyami/KiteAudio/issues).

## Contribute

File one root cause per [GitHub issue](https://github.com/yuroyami/KiteAudio/issues/new).
Include your OS and device, target, sample rate, channel layout, buffer size, callback
behavior and the relevant events. A bounded reproduction using a fake backend is useful
for lifecycle or concurrency failures; distinguish it from an observed hardware failure.
For device reports, include the route and whether the failure occurs on startup,
interruption, stop, restart or removal.

## License

[Apache-2.0](../LICENSE).

`kiteaudio-output` is part of [KiteAudio](../README.md). The other KiteAudio modules are listed
there, among them [`kiteaudio-synth`](../kiteaudio-synth/README.md) and
[`kiteaudio-midi`](../kiteaudio-midi/README.md).
