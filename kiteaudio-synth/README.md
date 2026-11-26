# kiteaudio-synth

`kiteaudio-synth` is the synthesizer module of [KiteAudio](../README.md). It is a sample-based
synthesizer written in common Kotlin. It reads SoundFont banks, takes MIDI messages and renders
stereo Float32 PCM from `commonMain`.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
and review of its author.</sub>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](../LICENSE)

## What you get

`kiteaudio-synth` brings its own synthesis engine. It depends only on `kotlin-stdlib`, with no JNI,
platform synthesizer or bundled SoundFont. Your app supplies the font bytes and the audio output.
[`kiteaudio-output`](../kiteaudio-output/README.md) sends PCM to a device, and
[`kiteaudio-midi`](../kiteaudio-midi/README.md) handles MIDI files and connections.

| Task | What `kiteaudio-synth` provides |
| --- | --- |
| Read | SF2 presets, instruments, zones, generators, modulators, INFO text, 16-bit sample frames and the `sm24` low bytes of 24-bit samples. SF3 compressed samples can be retained for an external decoder. |
| Play | Layered sample voices, velocity and key ranges, loop modes, exclusive classes, a fixed voice pool and voice stealing. |
| Shape | Volume and modulation envelopes, two LFOs, a resonant low-pass filter, linear, cubic or band-limited interpolation, a choice of how loops read their guard frames, stereo reverb and chorus. |
| Control | 16 channels, programs, bank selection, percussion channels, sustain and sostenuto, volume, expression, pan, pressure and pitch bend. |
| Tune | RPN pitch-bend range, fine and coarse channel tuning, an A4 reference and a per-key temperament table. |
| Keep and combine | `SoundFontWriter.write`, `merged` and `pruned`, preserving sample data (24-bit samples included) and renumbering references. |

Here is the whole path from a font to an audio block:

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.Synth

fun renderFirstNote(sf2Bytes: ByteArray): FloatArray {
    val font = KiteSynth.readFont(sf2Bytes)
    val synth = Synth(font, sampleRate = 48_000, maxVoices = 64)
    synth.noteOn(channel = 0, key = 60, velocity = 100)
    return FloatArray(512 * 2).also { buffer ->
        synth.render(buffer, frames = 512)
    }
}
```

The result is 512 **frames**, each containing a left and a right sample. Creating the synth loads
and prepares its regions; a callback should reuse that synth and its output buffer.

> [!NOTE]
> The version is `0.3.0`. The API can still change, and [CHANGELOG.md](../CHANGELOG.md) lists the
> breaking changes.

## Install

`kiteaudio-synth` is on Maven Central. Add the dependency to your shared code:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteaudio-synth:0.3.0")
        }
    }
}
```

For a plain Android or JVM application, put the same dependency in its usual `dependencies { }`
block. With the Kotlin Toolchain, add it to `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-synth:0.3.0
```

The coordinates are `io.github.yuroyami:kiteaudio-synth:0.3.0`. The version is `publishing.version` in
`library.module-template.yaml`.

## A quick tour

### Load and choose a sound

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.SoundFont

fun inspectFont(bytes: ByteArray): SoundFont {
    val font = KiteSynth.readFont(bytes)
    for (preset in font.presets) {
        println("${preset.name}: bank ${preset.bank}, program ${preset.program}")
    }
    return font
}
```

`readFont` is synchronous. For malformed input it is intended to throw
`SoundFontFormatException`, whose `offset` identifies the byte position when available. Loading
is eager: every sample gets its own array, even when file ranges overlap. Keep file loading,
SF3 decoding and font preparation away from the audio callback.

Programs, channels and MIDI keys use zero-based numeric MIDI values. A melodic channel reads its
bank from CC 0; CC 32 is stored but does not contribute to bank lookup. If a bank lacks the requested
program, lookup tries bank 0 and then the lowest bank/program preset.

```kotlin
synth.controlChange(channel = 0, controller = 0, value = 1)
synth.programChange(channel = 0, program = 5)
synth.noteOn(channel = 0, key = 60, velocity = 100)
```

These messages become effective during the next render. `presetOn`, `programOn`, `bankOn` and
`controller` report the state that has already been applied.

### Place a note within a block

```kotlin
val blockFrames = 512
val buffer = FloatArray(blockFrames * 2)

synth.noteOn(channel = 0, key = 60, velocity = 100, atFrame = 128)
synth.noteOff(channel = 0, key = 60, atFrame = 384)
synth.render(buffer, frames = blockFrames)
```

`atFrame` is relative to the next render, not an absolute song position or a timestamp.
Negative offsets clamp to zero; offsets beyond the block carry into later renders. Messages at
the same frame keep their arrival order. The queue holds 1,024 events. `tryEnqueue(...)` returns
`false` when full; `enqueue` and the convenience note/control methods throw `IllegalStateException`.
Rejection increments `rejectedEvents` and leaves accepted events and voices unchanged. Use
`tryEnqueue` on a deadline-sensitive path, render regularly, and handle a rejected note-off
explicitly (for example, with the host's panic policy). No event is applied early to make space.

For a message from a MIDI parser or input connection, pass the status and data bytes directly:

```kotlin
synth.enqueue(status = 0x90, data1 = 60, data2 = 100, atFrame = 0)
synth.enqueue(status = 0xD0, data1 = 64, data2 = 0, atFrame = 0) // channel pressure
```

`Synth` accepts channel voice messages; it does not parse MIDI byte streams, play an SMF timeline
or interpret SysEx/UMP messages. Those responsibilities belong to the caller or a MIDI library,
for example `kiteaudio-midi`.

### Use pedals, drums and tuning

```kotlin
synth.controlChange(channel = 0, controller = 64, value = 127) // sustain down
synth.controlChange(channel = 0, controller = 64, value = 0)   // sustain up
synth.controlChange(channel = 0, controller = 66, value = 127) // capture held keys with sostenuto
synth.pitchBend(channel = 0, value = 4096)                     // halfway up; range defaults to 2 semitones

synth.setPercussion(channel = 8, percussion = true)
synth.programChange(channel = 8, program = 0)
synth.noteOn(channel = 8, key = 36, velocity = 100)

synth.referenceA4 = 432.0
synth.tuningCents[60] = -50.0
```

Channel 9 starts as percussion. Percussion channels use bank 128; a missing kit falls back to kit
0, and a font without a kit produces silence on those channels. The A4 reference and temperament
table affect subsequently started melodic notes. Held notes keep that tuning; pitch bend and RPN
channel tuning still move them.

`allNotesOff(channel)` releases notes and clears pedals. `allSoundOff()` immediately stops every
voice, clears the shared effects and empties the event queue. `allSoundOff(channel)` stops that
channel's voices; the effects are shared, so it does not remove only that channel's existing tail.

### Adjust the output

```kotlin
import io.github.yuroyami.kiteaudio.synth.Interpolation
import io.github.yuroyami.kiteaudio.synth.LoopBoundary

synth.interpolation = Interpolation.Linear
synth.loopBoundary = LoopBoundary.Wrapped
synth.reverb.wet = 0.0
synth.reverb.dry = 1.0
synth.chorus.level = 0.0
```

Cubic interpolation is the default. At a loop boundary, the interpolation reads the frames just
outside the loop, the guard frames. SoundFont 2.04 asks font authors to make the frames around the
loop start match the frames around the loop end. `LoopBoundary.GuardFrames` is the default and reads
the guard frames as the font stores them. `LoopBoundary.Wrapped` reads the loop as a circle after
the first wrap, so guard frames that do not match the loop cannot change its sound. Choose it for a
font whose loops click or sound different from what its author heard. The attack before the first
wrap and the tail after a released loop do not change. Each render reads `interpolation` and
`loopBoundary`, so a change also reaches the notes that sound.

#### Band-limited interpolation

A voice reads its sample by a step: the number of sample frames for each output frame. A note above
the pitch of its sample has a step above 1. Linear and cubic interpolation then fold the content
above the output's Nyquist limit back into the audible band as aliases. The low-pass filter of a
zone runs after the read, so it cannot remove them.

`Interpolation.BandLimited` reads through a windowed sinc low-pass. The cutoff is the Nyquist limit
of the sample for a step of 1 or less. For a larger step, the cutoff is that limit divided by the
step, which is the output's Nyquist limit. The cutoff follows the step while the note sounds, so a
bend, an LFO or the modulation envelope moves it without a click. The kernel is a Kaiser window
(beta 10) with 16 zero crossings on each side, from a table of 512 entries for each zero crossing.

| Property | `Interpolation.BandLimited` |
| --- | --- |
| Aliases | At least 96 dB below the tone for every alias that lands below 0.8 of the output's Nyquist limit, which is 19.2 kHz at 48 kHz. The worst measured case is -100 dB. `Cubic` leaves the same aliases at 0 to -6 dB. |
| Transition band | 0.8 to 1.2 times the cutoff. Content in this band is partly removed, and the part above the cutoff folds back above 0.8 of the output's Nyquist limit. |
| Passband | Within 0.01 dB up to 0.8 times the cutoff. A tone at 0.9 times the cutoff loses about 0.5 dB. |
| Largest step | 4, two octaves above the pitch of a sample at the output's rate. Above a step of 4, the cutoff stays at a quarter of the sample's Nyquist limit, and aliases return. |
| Latency | Zero. The whole sample is in memory, so the kernel reads ahead of the position. |
| CPU time | The JVM benchmark renders a General MIDI load, where most notes play below the pitch of their sample. For that load, the mode takes about 2.2 times the time of `Cubic` on an Apple M2 and about 3.4 times on an x64 Linux machine. A read takes 32 taps for a step of 1 or less, then 32 times the step, up to 128 at a step of 4. On the M2, one read takes about 30 ns at a step of 1 and about 130 ns at a step of 4. |
| Memory | One table of 64 KiB that every voice shares. The first time a synth selects the mode, it builds the table, so select it on the control thread. |

At a loop boundary, every tap of the kernel follows the rule that `loopBoundary` sets for the cubic
taps. A loop shorter than the kernel wraps as many times as the kernel needs. A released
`UntilRelease` loop reads on into its tail, and the tail reads the stored frames in both
`LoopBoundary` modes. `Voice.render` always reads with cubic interpolation, so only
`Synth.interpolation` selects `BandLimited`.

Setting the reverb's wet level to zero does not reset its dry
level: use `dry = 1.0` too when you want the unattenuated dry mix. Voices are mixed into Float32 PCM;
many loud voices can exceed the nominal `-1f..1f` range. The host owns any limiter, master gain or
conversion to an integer device format.

### Write, merge and prune fonts

```kotlin
import io.github.yuroyami.kiteaudio.synth.SoundFontWriter
import io.github.yuroyami.kiteaudio.synth.merged
import io.github.yuroyami.kiteaudio.synth.pruned

val bank = piano.merged(generalMidi)
val compact = bank.pruned()
val sf2Bytes = SoundFontWriter.write(compact)
```

The first font wins a bank/program collision. Merge and prune renumber instruments, samples and
links, and merge shares frame arrays with the inputs. The writer removes zones without a target
and extracts shared zone values into global zones. It rejects record counts that cannot fit the
format's 16-bit indexes.

### Decode SF3 samples

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.SoundFont

fun decodeSf3(
    bytes: ByteArray,
    decodeVorbis: (ByteArray) -> ShortArray,
): SoundFont = KiteSynth.readFont(bytes, keepCompressed = true).decoded(decodeVorbis)
```

The callback receives one compressed sample's Ogg Vorbis bytes and returns decoded **mono 16-bit
frames**. It is synchronous and called once per compressed sample. `kiteaudio-synth` supplies no Vorbis
decoder; the decoder and its resource limits are the caller's responsibility. Without
`keepCompressed`, the reader rejects compressed samples, and `Synth` refuses a still-compressed
font. `SoundFontWriter` can preserve a font loaded with its compressed samples intact.

## Platforms

All production code is common Kotlin in `kiteaudio-synth/src`. These are the targets declared by the current build:

| Target | Details |
| --- | --- |
| Android | Android KMP library, minSdk 23 and compileSdk 37.2. The host provides audio and MIDI access. |
| JVM | Java 21 toolchain. |
| iOS devices | `iosArm64`. `.github/scripts/apple_frameworks.py` links a dynamic framework named `KiteSynth`. |
| iOS simulators | `iosSimulatorArm64` and `iosX64`. The script links the same framework name. |
| macOS Native | `macosArm64`. The script links the same framework name. |
| tvOS and watchOS | `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64` and `watchosSimulatorArm64`. CI builds these klibs but runs no tests on them. |
| Web | `js`, `wasmJs` and `wasmWasi`. Rendering is the same common code; sending the samples to Web Audio is the app's job. |
| Linux Native | `linuxX64` and `linuxArm64`. |
| Windows Native | `mingwX64`. |
| Android Native | `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86` and `androidNativeX64`. CI builds these klibs but runs no tests on them. |

The build leaves out `macosX64`, because Kotlin deprecates Intel macOS. A desktop app on Linux, Windows or an Intel Mac can also use
the JVM artifact.

A declared target establishes build intent. It does not establish device latency, callback safety,
audio fidelity or runtime parity. `readFont`, `Synth` construction and `replaceFont` export their
expected errors through `@Throws`, so Swift can use `do`/`try`/`catch`. Swift reads a synth's font
through `currentFont()` and replaces it through `try replaceFont(newFont:)`; the Kotlin `font`
property is hidden from Objective-C because a property setter cannot export a throwing contract.
Kotlin and JVM callers keep ordinary property access.

## Buffers, threads and ownership

Use one owner thread for a `Synth`, or serialize access outside the callback. The event arrays,
voices, channels and effect buffers are mutable and are not protected for concurrent producers.
In particular, calling `enqueue` on a MIDI-input thread while another thread runs `render` is not
a supported concurrent queue. Kotlin/Native's memory manager does not add synchronization.

- Construct or replace a font outside the callback, with rendering stopped during replacement.
  Preparation allocates; successful replacement stops the current performance. A rejected
  replacement leaves the current font and performance intact.
- Reuse a `FloatArray` holding at least `frames * 2` values and pass a non-negative frame count.
  `render` overwrites that prefix; any unused suffix stays untouched.
- Rendering and normal event queueing use preallocated state. `soundingKeys`, font transforms,
  construction, decoding and `voicesFor` allocate and should stay off the callback.
- Treat a font's lists, generator maps and sample arrays as read-only while any synth uses them.
  Public arrays remain mutable, and merged fonts share sample frames.
- `kiteaudio-synth` opens no file, audio device, coroutine or worker thread. There is no `close` operation;
  the host manages its own device, scheduling and lifetime.

## Known limits

| Topic | Current behavior |
| --- | --- |
| SoundFont precision | Playback uses the 16-bit `smpl` frames only. The reader keeps a valid `sm24` chunk in `SampleHeader.lowBytes`, and the writer writes it back, so a read and write keeps every bit of a 24-bit font. `frame24` gives the full signed value of one frame. The low bytes cost one more byte for each frame of a 24-bit font, and nothing for a 16-bit font. |
| Modulators | Generator destinations and linked modulators are evaluated (SoundFont 2.04, section 9.5.4). A linked modulator feeds its output to the modulator that its link names, and a chain of links can have any length. The target must have the link source as its primary source. The output reaches the target unchanged: the curve, polarity and direction bits of a link source are not applied. A modulator in a cycle, a link to itself or past the end of its list, a link into a modulator that reads a controller, and every modulator that feeds one of these add nothing. The reader and the writer keep every modulator. The reader stores a link past the end of its list as `0x7FFF`. A preset-level link counts places in the preset zone's own list and does not change the amount of an instrument-level link. |
| Stereo sample links | Merge and prune preserve links. `Synth` plays a valid stereo pair as one pair, as SoundFont 2.04 section 7.10 says. A pair is valid when a left and a right sample link to each other, and one instrument zone for each matches the key and velocity of the note. Both sides start on the same frame and take every pitch value from the right sample's zone: tune, root key, scale tuning, the sample's rate and pitch correction, the vibrato LFO, and the modulation envelope and LFO as they move the pitch. Each side keeps its own volume, filter, envelopes, sends and pan. A pair with unequal lengths still plays as a pair, and each side ends at its own end. Any other link plays each zone as its own voice, with its own pitch, as a mono sample does. If one side of a pair is stolen, the other side plays on at the pitch of the pair. `voicesFor` returns each zone's own plan and does not pair them. Linked chains of more than two samples play as separate voices. |
| Interpolation | `BandLimited` stops lowering its cutoff at a step of 4, so aliases return above it. `Voice.render` reads with cubic interpolation, and only `Synth.interpolation` selects `BandLimited`. |
| Envelope timing | Envelopes and LFOs update every 16 output frames. Envelope times are capped near 100 seconds. |
| MIDI scope | Channel voice messages only. Bank selection uses CC 0, and there is no portamento/legato mode, NRPN synthesis control, MPE manager or MIDI 2.0 interface. |
| Resource bounds | Reading defaults to 64 MiB of cumulative retained sample payload, counting overlapping ranges separately and the low bytes of 24-bit samples. Preparation defaults to 4,194,304 examined zone pairs, 16,384 regions and 1,048,576 retained modulator entries. Explicit overloads allow larger trusted fonts; limits are checked before expansion. SF3 decoding still needs a separate host-owned bound. |
| Output and integration | No bundled sound bank, audio output backend, file I/O, Compose UI or timeline player. |
| Verification | Tests use synthetic fonts. CI runs them on the JVM, on JS, wasmJs and wasmWasi in Node, and on `macosArm64`, `linuxX64` and `mingwX64` (see [Build and test](#build-and-test)). There is no differential suite against a reference synthesizer and no audio test on a physical device. |

For a larger trusted bank, supply explicit budgets:

```kotlin
val font = KiteSynth.readFont(bytes, maxSampleBytes = 128L * 1024 * 1024)
val synth = Synth(font, sampleRate = 48_000, maxVoices = 256,
    maxPreparedRegions = 32_768, maxPreparedModulators = 2_097_152,
    maxPreparedPairs = 8_388_608L)
```

The reader rejects zero sample rates and unsigned rates that do not fit a positive Kotlin `Int`.
Valid high rates such as 96 kHz remain supported. Quiet releases keep their authored envelope
and sample lifetime, so a passage with many releases can use more CPU time. Effect sends use
SoundFont units directly.

Java callers see declared checked exceptions on the `@Throws` entry points and may need to
catch or declare them. Kotlin callers keep the ordinary exception rules of Kotlin.

## Build and test

Use the `./kotlin` script in the repository root (`kotlin.bat` on Windows). It downloads the JDK
and Kotlin/Native that it needs. Android builds need an Android SDK with API level 37.2. Apple builds
and Apple native tests need macOS and a compatible Xcode installation.

```sh
./kotlin test -m kiteaudio-synth -p jvm
./kotlin test -m kiteaudio-synth -p macosArm64
python3 .github/scripts/web-tests/run.py --module kiteaudio-synth
./kotlin build -m kiteaudio-synth -p iosSimulatorArm64
python3 .github/scripts/apple_frameworks.py --platforms iosSimulatorArm64 --build-type debug --no-xcframework
./kotlin do dokka
```

The `web-tests` script runs the JS, wasmJs and wasmWasi tests in Node. The Kotlin Toolchain does not link
Apple frameworks. The `apple_frameworks.py` script links `KiteSynth.framework` from the klibs that
`./kotlin build` writes. `./kotlin check abi -m kiteaudio-synth` and
`./kotlin check klibAbi -m kiteaudio-synth` compare the public API with the dumps in
`kiteaudio-synth/api`. Build every platform first, because the klib check reads the built klibs.
A Mac can build the klibs of every platform. The `linuxX64` tests run only on Linux, and the
`mingwX64` tests run only on Windows.

The workflow `.github/workflows/instruments.yml` runs the tests in CI on the JVM, on JS, wasmJs
and wasmWasi in Node, and on `macosArm64`, `linuxX64` and `mingwX64`. It also runs the public API checks and
the Android build.

Common tests construct SoundFont bytes deliberately, then check parsing, round trips, channels,
voice selection, envelopes, filtering and effects. JVM tests also write a short demonstration WAV
under the system temporary directory and measure a 96-voice render workload. The benchmark's
machine-specific assertions are disabled when `CI` is set; its numbers are not a device guarantee.

Known defects, open proposals and platform limits are tracked as
[GitHub issues](https://github.com/yuroyami/KiteAudio/issues).

## Contribute

Open an [issue](https://github.com/yuroyami/KiteAudio/issues) with the smallest font or MIDI
sequence that shows the problem, the sample rate and render block size, target and toolchain,
and the observed and expected behavior. A generated fixture is welcome when a real SoundFont
cannot be redistributed. For an audio mismatch, a short output WAV and a reference render help.

Keep shared synthesis and format logic in `kiteaudio-synth/src`; add a focused regression test for the
public path that changes. Public API snapshots live under [`kiteaudio-synth/api`](api), and
API changes should include an entry in [CHANGELOG.md](../CHANGELOG.md).

## License

Apache-2.0. See [LICENSE](../LICENSE). `kiteaudio-synth` bundles no SoundFont bank; fonts and decoders supplied
by an application have their own licenses.

`kiteaudio-synth` is part of [KiteAudio](../README.md). The other KiteAudio modules are listed
there, among them [`kiteaudio-midi`](../kiteaudio-midi/README.md) and
[`kiteaudio-output`](../kiteaudio-output/README.md).
