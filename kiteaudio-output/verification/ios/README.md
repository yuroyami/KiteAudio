# iOS verification

An iPhone app for what the macOS tests cannot check: how the output ends a stream on a real iPhone when the system takes the audio away.

## What the check proves

On iOS the output ends a running stream with `DeviceLost` and then `Stopped` in these cases:

- An interruption of the audio session begins, for example an alarm, a timer or a phone call.
- A route change removes the output device, for example headphones that you disconnect.
- The system stops the AudioQueue of the stream.

After `Stopped`, `isRunning` is false and the output stays silent. It does not resume by itself. The app calls `start()` again to play.

The app has one screen. It shows a Start button, a Stop button, the current value of `isRunning`, and a log. Each log line starts with the seconds since the app opened. The app reads `isRunning` four times a second and logs each change.

- Lines that start with `event` come from the output, one line for each `AudioOutputEvent`.
- Lines that start with `session:` or `app:` come from the app itself. They show what the system did, so you can compare.

The app plays a quiet 440 Hz tone at 48000 Hz, stereo. The output leaves the audio session to the app. So before each start, the app sets the `playback` category and activates the session. The app has no background audio mode, so iOS suspends it in the background.

### What the check cannot prove

- The `DeviceUnderrun` estimate on iOS. The app logs `DeviceUnderrun` events, but nothing compares them with a counter.
- A loss or a reset of the system media services. The sitting does not cause one.
- Anything that a person does not see or hear. The check is by eye and by ear.

## Build

The app is a plain Swift app. It does not use the Kotlin Toolchain. `build.sh` gets the library this way:

1. It compiles kiteaudio-core and kiteaudio-output for `iosArm64` in the KiteAudio project.
2. It links `KiteAudioOutput.framework` with `.github/scripts/apple_frameworks.py`. The framework goes to `kiteaudio-output/build/bin/iosArm64/releaseFramework`.
3. It builds `OutputCheck.xcodeproj`, which links and embeds that framework.

The app runs on a real iPhone only, with iOS 15.0 or later. It does not build for a simulator.

### A build without signing

This build checks that the app compiles and links. It cannot go on a phone. Run it from the repository root:

```sh
kiteaudio-output/verification/ios/build.sh
```

### A signed build

Signing needs the ID of an Apple development team. Xcode shows it in Settings > Accounts, and the Apple Developer site shows it on the Membership page. Do not write the team ID into a file of the repository. Give it in the environment instead:

```sh
KITEAUDIO_TEAM_ID=<team ID> kiteaudio-output/verification/ios/build.sh
```

Xcode signs the app with automatic signing, and makes a development profile when it needs one. The bundle identifier is `io.github.yuroyami.kiteaudio.verification.output`. Connect the iPhone before this build if the team does not know the iPhone yet. Then Xcode can register it.

### Install and open

Connect the iPhone with a cable and unlock it, then run:

```sh
kiteaudio-output/verification/ios/run.sh
```

The script finds the one connected iPhone, installs the app and opens it. It runs these two commands, where `<device>` is the identifier that `xcrun devicectl list devices` shows:

```sh
xcrun devicectl device install app --device <device> kiteaudio-output/verification/ios/build/DerivedData/Build/Products/Debug-iphoneos/OutputCheck.app
xcrun devicectl device process launch --device <device> io.github.yuroyami.kiteaudio.verification.output
```

The iPhone needs Developer Mode, in Settings > Privacy & Security. With a personal team, iOS asks you to trust the developer first. Do it in Settings > General > VPN & Device Management.

## The sitting

Have wired headphones or Bluetooth headphones ready. An iPhone without a headphone jack needs Lightning or USB-C headphones, or Bluetooth headphones.

Start and stop:

1. Run `run.sh`. The app opens.
2. Tap Start. Check: you hear the tone, the log shows `event Started`, and `isRunning` is true.
3. Tap Stop. Check: the tone stops, the log shows `event Stopped` and no `DeviceLost`, and `isRunning` is false.
4. Tap Start. Check: the tone plays again.

An interruption:

5. Ask Siri to set a timer for 15 seconds, then close Siri. Check: the tone plays on. Siri only lowers the volume for a moment, and that is not an interruption.
6. Wait for the timer. Check: when it rings, the log shows `session: interruption began`, then `event DeviceLost` and `event Stopped`. `isRunning` is false and the tone stops. Then stop the timer on its banner. Check: the log shows `session: interruption ended`, and the tone stays off.
7. Tap Start. Check: the tone plays and the log shows `event Started`.

A lost route:

8. Connect the headphones. Check: the tone moves to the headphones and plays on. The log shows a route change, but no `DeviceLost`.
9. Disconnect the headphones. For Bluetooth headphones, put them in their case or turn them off. Check: the log shows `session: route change, reason oldDeviceUnavailable`, then `event DeviceLost` and `event Stopped`. `isRunning` is false and the speaker stays silent.
10. Tap Start. Check: the tone plays from the speaker.

A suspension:

11. Swipe up from the bottom edge to go to the Home Screen. Check: the tone stops.
12. Wait 5 seconds, then open the app again.
13. Read the log and `isRunning`. Note which of the results below you see.
14. If `isRunning` is false, tap Start. Check: the tone plays.

## What a pass looks like

Each "Check" in the steps holds. In general:

- Every `DeviceLost` is followed by one `Stopped`, and `isRunning` turns false.
- A `stop()` by the app gives `Stopped` without `DeviceLost`.
- After `Stopped`, the tone stays off until you tap Start, and Start plays it again.
- `isRunning` is true only while you hear the tone.

After the suspension, two results are a pass. Either the log shows `event DeviceLost` and `event Stopped` and `isRunning` is false, or the tone plays again with `isRunning` true and no event. The result is a FAIL when `isRunning` is true while the tone is silent, or when Start does not bring the tone back. Write down which of the two passing results you saw.

If an interruption or a lost route gives a `DeviceLost` whose reason is "the system stopped the audio queue", that is also a pass. Then the queue stopped before the session notice arrived.
