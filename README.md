# KiteAudio

Audio libraries written in Kotlin for Kotlin Multiplatform. KiteAudio decodes and encodes
audio files, reads and writes MIDI, renders SoundFont instruments to PCM and plays PCM on an
output device. Each library is a separate artifact, so a project adds only the parts it needs.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## Modules

Every artifact has the group `io.github.yuroyami` and the version 0.3.0.

| Artifact | What it does | Targets |
|---|---|---|
| `kiteaudio-core` | The audio format types that the codec and the output module share: `AudioFormat`, `ChannelLayout`, `AudioChannel` and `PcmSource`. Depends on kotlin-stdlib only. | 22: JVM, Android, JS, wasmJs, wasmWasi, Apple, Linux, Windows, Android Native |
| `kiteaudio-codec` | Audio containers, codecs and tags. Depends on kotlin-stdlib and `kiteaudio-core` only. | The same 22 |
| `kiteaudio-codec-io` | The kotlinx-io bridge of the codec: reads a `Path` or `RawSource` and writes to a `Sink` or a `Path`. | The same 22 |
| [`kiteaudio-midi`](kiteaudio-midi/README.md) | Standard MIDI Files, typed MIDI messages, a tempo map and note pairing. Depends on kotlin-stdlib only. | The same 22 |
| `kiteaudio-midi-devices` | MIDI ports through MidiManager on Android, CoreMIDI on Apple and `javax.sound.midi` on the JVM. | JVM, Android, iOS, macOS |
| `kiteaudio-midi-coremidi` | CoreMIDI ports for a macOS desktop JVM, through the foreign function API. Needs JDK 22 or later at runtime. | JVM |
| [`kiteaudio-synth`](kiteaudio-synth/README.md) | SoundFont reading and writing, and a sample-based synthesizer that renders Float32 PCM. Depends on kotlin-stdlib only. | The same 22 |
| [`kiteaudio-output`](kiteaudio-output/README.md) | Callback-based mono and stereo audio output through Java Sound, AudioTrack, AudioQueue and Web Audio. | JVM, Android, iOS, macOS, JS, Wasm |

The README of each MIDI, synth and output module describes that module. The rest of this page
describes the two codec modules. The code and the docs name the codec AudioKodec.

## The codec

Audio containers, codecs and tags written in Kotlin for Kotlin Multiplatform: decode 22
containers to Float32 PCM, write FLAC, WavPack, WAV, AIFF, AU, Apple Lossless, Ogg Vorbis,
Ogg Opus and MP3, and edit tags without touching the audio, with the same code on every target.

**[Documentation](docs/index.md)**: a guide per task, a page for each format, and the full
support tables.

Kotlin Multiplatform has no library that decodes audio in common code. The existing choices
wrap each platform's own player, which gives you playback and not samples, and gives you
nothing at all on JS or WebAssembly. AudioKodec decodes in `commonMain` with no third-party
dependency, so the same call works on Android, iOS, the JVM, Linux, Windows, watchOS, in a
browser and in a WASI runtime. It depends on `kotlin-stdlib` and `kiteaudio-core`, which holds
the shared audio format types.

Every format listed here has tests, and most are checked sample for sample against ffmpeg or
the codec's own reference decoder.

## Install

0.3.0 is on Maven Central. Add the artifacts you need:

With Gradle:

```kotlin
dependencies {
    implementation("io.github.yuroyami:kiteaudio-codec:0.3.0")
    // Optional, for files through kotlinx-io:
    implementation("io.github.yuroyami:kiteaudio-codec-io:0.3.0")
}
```

With the Kotlin Toolchain, in `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-codec:0.3.0
  # Optional, for files through kotlinx-io:
  - io.github.yuroyami:kiteaudio-codec-io:0.3.0
```

The JVM artifacts need Java 21 or newer.

## Usage

### Decode a file

```kotlin
val audio = AudioKodec.decode(bytes)
println("${audio.format.sampleRate} Hz, ${audio.format.channels} channels")
println("${audio.durationSeconds} seconds")
```

`audio.samples` is interleaved Float32, nominally between -1.0 and 1.0. A lossless coding can
also hand back its stored integers untouched through `IntPcmAccess`. See [Decoding](docs/decoding.md).

### Read the header first

`probe` reads the header and the index tables without decoding the audio:

```kotlin
val info = AudioKodec.probe(bytes)
println(info.codec.displayName)       // FLAC
println(info.durationSeconds)
```

### Stream and seek

A whole-file decode allocates the whole file. For anything long, pull blocks instead. Seeking
is sample accurate, so the decoder lands on the frame you asked for and not on the block that
holds it.

```kotlin
AudioKodec.open(bytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate * 30L)
    while (true) {
        val block = decoder.nextFrame() ?: break
        play(block.samples, block.frameCount)
    }
}
```

### Write a file

```kotlin
val wav = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.wav(16))
val flac = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.flac(bitDepth = 24))
val ogg = AudioKodec.encode(samples, AudioFormat(44_100, 2), EncodeTarget.vorbis(quality = 4f))
```

The encoders write FLAC, WavPack, WAV, AIFF, AU, Apple Lossless in an `.m4a` file, Vorbis
and Opus in Ogg, and MP3, with tags and cover art. See [Encoding](docs/encoding.md).

### Read and edit tags

Tags work on any file, including one that AudioKodec cannot decode. An edit rewrites the tag
blocks and copies the audio across byte for byte, so a lossy file loses nothing.

```kotlin
val tags = AudioKodec.readTags(bytes)
println(tags[TagField.TITLE])

val edited = tags.edit {
    set(TagField.TITLE, "New title")
    set(TagField.TRACK_NUMBER, 4)
}
val updated = AudioKodec.writeTags(bytes, edited)
```

See [Tags](docs/tags.md) for merged and raw views, pictures, chapters and loudness values.

### Files on disk

The core never touches the filesystem. `kiteaudio-codec-io` adds the bridge to kotlinx-io, with a
seekable file source and an atomic write:

```kotlin
val info = Path("song.flac").openAudioSource().use { AudioKodec.probe(it) }

Path("out.wav").writeAudioAtomically { sink ->
    AudioKodec.encode(samples.asPcmSource(format), EncodeTarget.wav(24), sink)
}
```

See [Files and kotlinx-io](docs/files.md).

### More

[Change the sample rate](docs/resample.md), [mix channels](docs/mix.md),
[measure loudness](docs/loudness.md), [draw a waveform](docs/waveform.md), and
[apply levels and fades](docs/levels.md). The [task pages](docs/tasks/index.md) each answer
one "how do I" question in a few minutes, with code that runs in the build.

### Try it

The repository has a sample desktop viewer. Drop a file on its window, or use File > Open, to
see the container and coding, the format and duration, the tags and cover art, and the
waveform, and to play the file through Java Sound:

```sh
./kotlin run -m sample
./kotlin run -m sample -- path/to/song.flac
```

Without a display, it prints the same summary for the file named on the command line.

## Formats

- **Read and write:** FLAC, WavPack, WAV and RF64, AIFF and AIFF-C, AU, Apple Lossless in
  MP4, Vorbis and Opus (mono and stereo) in Ogg, and MP3 (MPEG-1 Layer III, mono and
  stereo). BW64 is read as well.
- **Read only:** Ogg with FLAC and Speex, MPEG audio Layers I and II and the MPEG-2 rates of
  Layer III,
  AAC-LC in MP4 and ADTS, AC-3, Matroska and WebM, Wave64, CAF, Monkey's Audio, True
  Audio, Musepack, AMR, GSM 06.10, iLBC, G.722, G.726, and DSD in DSF and DSDIFF.
- **Tags:** ID3v2, APE, Vorbis comments, MP4 items, RIFF INFO and `bext`, and AIFF text
  chunks are read and written. An ID3v1 block is kept up to date when a file has one. Lyrics3
  and Matroska tags are read.

HE-AAC, Enhanced AC-3, DTS and WMA are refused by name. [Formats](docs/formats.md) describes
each format, and [Format support](docs/support.md) has the full tables and how each one is
checked.

## Platforms

Both modules build for 22 targets:

| Family | Targets |
| --- | --- |
| Android, JVM | Android (minSdk 21), `jvm` (Java 21 or newer) |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Other native | `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| Web | `js` and `wasmJs` (browser and Node), `wasmWasi` (Node) |

`macosX64` is not built, because Kotlin deprecates Intel macOS. In a browser, `kiteaudio-codec-io`
reads and writes buffers and streams only, because a browser has no filesystem for kotlinx-io.

## Limits

- AudioKodec decodes and does not play. [Playing the result](docs/decoding.md#playing-the-result)
  shows the sink for each platform.
- Vorbis, Opus and MP3 are the only lossy encoders: Opus in its music mode (CELT) with one or
  two channels, and MP3 as MPEG-1 Layer III with one or two channels at 32, 44.1 and 48 kHz.
  AAC decodes only.
- A header cannot make AudioKodec allocate more than `DecodeOptions.maxAllocationBytes`, which
  defaults to 1 GiB.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the tests and the reference tools that
check each codec. Report a security problem as [SECURITY.md](SECURITY.md) describes. Open work
is in [GitHub Issues](https://github.com/yuroyami/KiteAudio/issues).

## License

Apache-2.0. See [LICENSE](LICENSE).

Most formats are written from their published specifications. Several decoders are
translations of permissively licensed reference code: Opus from libopus, Apple Lossless from
Apple's reference, WavPack, Monkey's Audio, True Audio, Musepack from libmpcdec, Speex from
libspeex, AMR from opencore-amr, GSM 06.10 from libgsm and iLBC from WebRTC. The Apple
Lossless and WavPack encoders come from the same references. The resampler comes from
speexdsp and the loudness meter from libebur128. [NOTICE](NOTICE) gives the
attribution and license terms for each, and
[reference/REFERENCES.md](reference/REFERENCES.md) records the sources.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ArchiveKodec](https://github.com/yuroyami/ArchiveKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).
