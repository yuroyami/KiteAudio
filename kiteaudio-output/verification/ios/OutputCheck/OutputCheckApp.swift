import AVFAudio
import KiteAudioOutput
import SwiftUI
import UIKit

@main
struct OutputCheckApp: App {
    @StateObject private var check = OutputCheck()

    var body: some Scene {
        WindowGroup { CheckView(check: check) }
    }
}

/// One screen: Start, Stop, the current isRunning, and a log of every event with a timestamp.
struct CheckView: View {
    @ObservedObject var check: OutputCheck

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Button("Start") { check.start() }.buttonStyle(.borderedProminent)
                Button("Stop") { check.stop() }.buttonStyle(.bordered)
                Spacer()
                Text("isRunning: \(check.isRunning ? "true" : "false")")
                    .font(.system(.body, design: .monospaced))
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(check.lines.enumerated()), id: \.offset) { index, line in
                            Text(line).font(.system(size: 12, design: .monospaced)).id(index)
                        }
                    }
                }
                .onChange(of: check.lines.count) { count in
                    proxy.scrollTo(count - 1, anchor: .bottom)
                }
            }
        }
        .padding()
    }
}

/// Owns the audio session setup, the output and the log. Runs on the main thread.
final class OutputCheck: ObservableObject {
    @Published private(set) var lines: [String] = []
    @Published private(set) var isRunning = false
    private let launched = Date()
    private var output: AudioOutput?
    private var observers: [NSObjectProtocol] = []
    private var timer: Timer?

    init() {
        log("ready: iOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)")
        watchSessionNotices()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.refresh() }
    }

    func start() {
        // The library leaves the session to the app, so the app sets the category and activates it.
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            log("session: activation failed: \(error.localizedDescription)")
        }
        do {
            let output = try self.output ?? open()
            self.output = output
            let format = output.format
            try output.start(callback: ToneRenderer(channels: format.channels, sampleRate: Double(format.sampleRate)))
            log("start() returned")
        } catch {
            log("start() threw: \(error.localizedDescription)")
        }
        refresh()
    }

    func stop() {
        output?.stop()
        log("stop() returned")
        refresh()
    }

    private func open() throws -> AudioOutput {
        let format = try AudioFormat(sampleRate: 48_000, channels: 2)
        let output = try Audio_appleKt.openAudioOutput(
            format: format, bufferFrames: 1024, device: nil, formatPolicy: .exact
        ) { [weak self] event in
            // Events arrive on a queue of the library. The log lives on the main thread.
            let text = String(describing: event)
            DispatchQueue.main.async {
                self?.log("event \(text)")
                self?.refresh()
            }
        }
        log("opened: format \(output.format), route \(routeName())")
        return output
    }

    private func refresh() {
        let running = output?.isRunning ?? false
        if running != isRunning {
            isRunning = running
            log("isRunning is \(running)")
        }
    }

    private func log(_ text: String) {
        let line = String(format: "%8.3f s  ", Date().timeIntervalSince(launched)) + text
        lines.append(line)
        NSLog("KiteAudioCheck %@", line)
    }

    private func routeName() -> String {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        return outputs.isEmpty ? "none" : outputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: " + ")
    }

    // These lines come from the app, not from the library. They show what the system did.
    private func watchSessionNotices() {
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            let info = note.userInfo ?? [:]
            let type = (info[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            switch type {
            case .began: self?.log("session: interruption began")
            case .ended:
                let options = AVAudioSession.InterruptionOptions(rawValue: info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                self?.log("session: interruption ended, shouldResume \(options.contains(.shouldResume))")
            default: self?.log("session: interruption of unknown type")
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 0
            self?.log("session: route change, reason \(routeChangeReason(raw)), now \(self?.routeName() ?? "")")
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) { [weak self] _ in
            self?.log("session: media services were reset")
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.log("app: entered the background")
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.log("app: back in the foreground")
        })
    }
}

private func routeChangeReason(_ raw: UInt) -> String {
    switch AVAudioSession.RouteChangeReason(rawValue: raw) {
    case .newDeviceAvailable: return "newDeviceAvailable"
    case .oldDeviceUnavailable: return "oldDeviceUnavailable"
    case .categoryChange: return "categoryChange"
    case .override: return "override"
    case .wakeFromSleep: return "wakeFromSleep"
    case .noSuitableRouteForCategory: return "noSuitableRouteForCategory"
    case .routeConfigurationChange: return "routeConfigurationChange"
    default: return "unknown (\(raw))"
    }
}

/// A quiet 440 Hz tone that fades in over 5 ms. The output calls it on its own serial render queue.
final class ToneRenderer: NSObject, AudioRenderCallback {
    private let channels: Int32
    private let step: Double
    private let fadeInFrames: Double
    private var phase = 0.0
    private var rendered = 0.0

    init(channels: Int32, sampleRate: Double) {
        self.channels = channels
        self.step = 2 * Double.pi * 440 / sampleRate
        self.fadeInFrames = sampleRate * 0.005
    }

    func render(out: KotlinFloatArray, frames: Int32) -> Int32 {
        for frame in 0..<frames {
            // A tone that starts at full volume clicks, so the app fades it in.
            let gain = min(1.0, rendered / fadeInFrames)
            rendered += 1
            let value = Float(sin(phase) * 0.03 * gain)
            for channel in 0..<channels {
                out.set(index: frame * channels + channel, value: value)
            }
            phase += step
            if phase >= 2 * Double.pi { phase -= 2 * Double.pi }
        }
        return frames
    }
}
