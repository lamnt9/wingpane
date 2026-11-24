import AppKit
import CoreGraphics
import Foundation

@MainActor public final class CaptureTestPattern {
    public static let shared = CaptureTestPattern()
    private var windows: [UInt32: NSWindow] = [:]

    private init() {}

    public func tick() {
        for window in windows.values { window.contentView?.needsDisplay = true }
    }

    public func show(on displayID: UInt32) {
        guard windows[displayID] == nil,
              let screen = NSScreen.screens.first(where: {
                  ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == displayID
              }) else { return }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let window = NSWindow(contentRect: screen.frame, styleMask: .borderless, backing: .buffered, defer: false, screen: screen)
        window.level = .screenSaver
        window.contentView = ClockView(frame: screen.frame)
        window.makeKeyAndOrderFront(nil)
        windows[displayID] = window
    }

    public func hide(on displayID: UInt32) {
        windows.removeValue(forKey: displayID)?.close()
    }
}

@MainActor private final class ClockView: NSView {
    private let began = Date()
    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.setFill(); bounds.fill()
        let elapsed = Date().timeIntervalSince(began)
        let x = CGFloat(elapsed.truncatingRemainder(dividingBy: 4) / 4) * bounds.width
        NSColor.systemCyan.setFill(); NSRect(x: x, y: bounds.midY - 18, width: 180, height: 36).fill()
        // Seven-segment digits avoid text/font setup in the headless daemon
        // while keeping the changing millisecond timestamp visible in captures.
        let digits = String(format: "%05d", Int(elapsed * 1000) % 100_000)
        let masks: [Character: UInt8] = [
            "0": 0b0111111, "1": 0b0000110, "2": 0b1011011, "3": 0b1001111, "4": 0b1100110,
            "5": 0b1101101, "6": 0b1111101, "7": 0b0000111, "8": 0b1111111, "9": 0b1101111,
        ]
        NSColor.white.setFill()
        for (index, digit) in digits.enumerated() {
            let mask = masks[digit, default: 0]
            let ox = CGFloat(36 + index * 52), oy = bounds.maxY - 130
            let segments: [NSRect] = [
                NSRect(x: ox + 8, y: oy + 58, width: 24, height: 6), NSRect(x: ox + 32, y: oy + 34, width: 6, height: 24),
                NSRect(x: ox + 32, y: oy + 6, width: 6, height: 24), NSRect(x: ox + 8, y: oy, width: 24, height: 6),
                NSRect(x: ox + 2, y: oy + 6, width: 6, height: 24), NSRect(x: ox + 2, y: oy + 34, width: 6, height: 24),
                NSRect(x: ox + 8, y: oy + 29, width: 24, height: 6),
            ]
            for bit in 0..<7 where mask & (1 << bit) != 0 { segments[bit].fill() }
        }
    }
}
