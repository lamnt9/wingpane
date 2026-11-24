import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit
import WingpaneCaptureKit
import WingpaneDisplayKit

struct Options {
    var width = 1280, height = 800, hz = 60.0, hidpi = false, bitrate = 20.0, seconds = 10.0, out = "/tmp/wp-capture.h264", animate = false
}
struct CLIError: Error { let text: String }
final class OutputState: @unchecked Sendable {
    let lock = NSLock(); var count = 0; var keys = 0; var bytes = 0; var latencies: [Int64] = []
    func append(_ frame: EncodedFrame, to handle: FileHandle) {
        lock.withLock { try? handle.write(contentsOf: frame.annexB); count += 1; bytes += frame.annexB.count; if frame.isKeyframe { keys += 1 }; latencies.append(frame.encodeLatencyUs) }
    }
    func snapshot() -> (Int, Int, Int, [Int64]) { lock.withLock { (count, keys, bytes, latencies.sorted()) } }
}
func parse() throws -> Options {
    var o = Options(); var i = 1; let a = CommandLine.arguments
    while i < a.count {
        let k = a[i]; if k == "--hidpi" { o.hidpi = true; i += 1; continue }; if k == "--animate" { o.animate = true; i += 1; continue }
        guard i + 1 < a.count else { throw CLIError(text: "missing value for \(k)") }; let v = a[i+1]
        switch k { case "--width": o.width = Int(v) ?? 0; case "--height": o.height = Int(v) ?? 0; case "--hz": o.hz = Double(v) ?? 0; case "--bitrate-mbps": o.bitrate = Double(v) ?? 0; case "--seconds": o.seconds = Double(v) ?? 0; case "--out": o.out = v; default: throw CLIError(text: "unknown option \(k)") }; i += 2
    }
    guard o.width > 0, o.height > 0, o.hz > 0, o.seconds > 0, o.bitrate > 0 else { throw CLIError(text: "invalid numeric option") }; return o
}

final class ClockView: NSView {
    private let began = Date()
    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.setFill(); bounds.fill()
        let elapsed = Date().timeIntervalSince(began)
        let barX = CGFloat(elapsed.truncatingRemainder(dividingBy: 4) / 4) * bounds.width
        NSColor.systemCyan.setFill(); NSRect(x: barX, y: bounds.midY - 18, width: 180, height: 36).fill()
        let label = String(format: "%.3f ms", elapsed * 1000) as NSString
        label.draw(at: NSPoint(x: 36, y: bounds.maxY - 110), withAttributes: [.font: NSFont.monospacedSystemFont(ofSize: 64, weight: .bold), .foregroundColor: NSColor.white])
    }
}

@MainActor func run() async throws {
    let o = try parse(); let display = try VirtualDisplay(width: o.width, height: o.height, refreshHz: o.hz, hiDPI: o.hidpi)
    defer { display.close() }
    let mode = CGDisplayCopyDisplayMode(display.displayID); let w = mode?.pixelWidth ?? o.width; let h = mode?.pixelHeight ?? o.height
    print("Display ID: \(display.displayID), pixels: \(w)x\(h)")
    var window: NSWindow?
    if o.animate {
        let app = NSApplication.shared; app.setActivationPolicy(.accessory)
        guard let screen = NSScreen.screens.first(where: { ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == display.displayID }) else { throw CLIError(text: "AppKit cannot find the virtual display screen") }
        let win = NSWindow(contentRect: screen.frame, styleMask: .borderless, backing: .buffered, defer: false, screen: screen); win.level = .screenSaver; win.contentView = ClockView(frame: screen.frame); win.makeKeyAndOrderFront(nil); window = win
        Timer.scheduledTimer(withTimeInterval: 1 / o.hz, repeats: true) { _ in MainActor.assumeIsolated { win.contentView?.needsDisplay = true } }
    }
    let capture = CaptureEncoder(displayID: display.displayID, width: w, height: h, hz: o.hz, bitrate: Int(o.bitrate * 1_000_000))
    let file = FileManager.default.createFile(atPath: o.out, contents: nil); guard file else { throw CLIError(text: "cannot create output file") }
    let handle = try FileHandle(forWritingTo: URL(fileURLWithPath: o.out)); let state = OutputState()
    do {
        try await capture.start { frame in state.append(frame, to: handle) }
    } catch {
        if CGPreflightScreenCaptureAccess() == false { fputs("Screen Recording permission required: System Settings › Privacy & Security › Screen & System Audio Recording → enable the terminal app\n", stderr); exit(2) }
        throw error
    }
    print("Low-latency rate control requested (session created with spec): \(capture.lowLatencyRateControlAccepted)")
    try await Task.sleep(for: .seconds(o.seconds)); await capture.stop(); window?.close(); try handle.close()
    let (n, k, b, ls) = state.snapshot()
    func percentile(_ p: Double) -> Double { guard !ls.isEmpty else { return 0 }; return Double(ls[min(ls.count - 1, Int(Double(ls.count - 1) * p))]) / 1000 }
    let captured = capture.framesCaptured
    print("Frames captured: \(captured)"); print("Frames encoded: \(n)"); print(String(format: "Effective fps: %.2f", Double(n) / o.seconds)); print("Keyframes: \(k)")
    print(String(format: "Encode latency p50/p95/max: %.3f / %.3f / %.3f ms", percentile(0.50), percentile(0.95), Double(ls.last ?? 0) / 1000))
    print("Bytes: \(b)"); print(String(format: "Average Mbps: %.3f", Double(b) * 8 / o.seconds / 1_000_000))
}

do { try await run() } catch { fputs("wp-capture: \(error)\n", stderr); exit(1) }
