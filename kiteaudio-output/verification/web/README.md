# Web verification

A browser page for what the web tests cannot check: that the Web Audio output plays through a real browser.

## Link the test modules

The page loads exports from the linked web test modules, so link them first, from the repository root:

```sh
./kotlin task :kiteaudio-output:linkJsTest
./kotlin task :kiteaudio-output:linkWasmJsTestRelease
```

## In a browser

Serve the repository root and open the page from localhost, which a browser treats as a secure context:

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

Then open `http://localhost:8765/kiteaudio-output/verification/web/index.html` and choose a technique: Auto, Worklet or Buffers. Each Play button opens an output by it, plays a quiet 440 Hz tone for three seconds and stops it from inside the renderer, then prints the latency the output started with and every underrun, device underrun, overrun and failure. Listen as well: the report cannot hear a click.

The Play buttons are clicks, so the browser lets them play at once. To check a start made before any click, open the page as `index.html?early=js` or `index.html?early=wasmJs`, optionally with `&mode=Buffers`. The page starts an output as it loads, which should wait without playing, and the Play button for that platform starts the same output again, which should play. A browser that remembers earlier visits may let the first start play at once.

To see the fallback to buffers, open the page from another machine on the network over plain HTTP, which is not a secure context, and play with Auto. For a hidden tab, switch away during a run. Browsers slow page timers in a hidden tab, so a Buffers run is expected to report device underruns there and a Worklet run none, but neither has been checked yet.

## Choosing an output

List outputs fills the Output menu from `enumerateDevices()`, through each platform's own listing, as an app would through `audioOutputDevices()`. The names stay empty until the page has a media permission; Ask for permission requests one through the microphone, and the page releases the microphone at once. List again after granting it. Each Play button then opens on the chosen output. Id by hand passes an id that no listing holds, which the output should refuse with `IllegalArgumentException`.

A browser whose `AudioContext` takes a `sinkId`, which is Chromium 110 and newer, should play on the chosen output. Any other browser should refuse a non-default entry with `UnsupportedOperationException`. To check a lost device, connect a second output, choose it, play for 30 seconds and unplug it during the run. Chromium 128 and newer should report `DeviceLost`, and the output stays open, so Start again then starts it once more, which should report `Failed`.

`globalThis.kiteAudioOutputReport` receives the playback report, and `globalThis.kiteAudioOutputDevicesListed` the listing as JSON, so the page can be driven from a browser's own automation as well.
