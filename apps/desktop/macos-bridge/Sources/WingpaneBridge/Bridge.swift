import Foundation
import AppKit
import WingpaneCaptureKit
import WingpaneDisplayKit

public typealias WPFrameCallback = @convention(c) (
    UnsafeMutableRawPointer?, UnsafePointer<UInt8>?, Int, Int32, Int64, Int64
) -> Void

private final class CaptureHandle: @unchecked Sendable {
    let encoder: CaptureEncoder
    init(_ encoder: CaptureEncoder) { self.encoder = encoder }
}
private final class CallbackBox: @unchecked Sendable {
    let callback: WPFrameCallback
    let context: UnsafeMutableRawPointer?
    init(_ callback: @escaping WPFrameCallback, _ context: UnsafeMutableRawPointer?) {
        self.callback = callback; self.context = context
    }
}
private final class StartupResult: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set(_ result: Bool) { lock.lock(); value = result; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}

private final class CaptureRegistry: @unchecked Sendable {
    static let shared = CaptureRegistry()
    private let lock = NSLock()
    private var items: [UInt64: CaptureHandle] = [:]
    private var next: UInt64 = 1
    func insert(_ encoder: CaptureEncoder) -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        let id = next; next &+= 1
        items[id] = CaptureHandle(encoder)
        return id
    }
    func get(_ id: UInt64) -> CaptureHandle? { lock.lock(); defer { lock.unlock() }; return items[id] }
    func remove(_ id: UInt64) -> CaptureHandle? { lock.lock(); defer { lock.unlock() }; return items.removeValue(forKey: id) }
}

/// UI work is queued on the main thread and never synchronously awaited by the
/// daemon's async executor. C callers may safely invoke these from worker threads.
@_cdecl("wp_test_pattern_show")
public func wp_test_pattern_show(_ displayID: UInt32) {
    DispatchQueue.main.async { MainActor.assumeIsolated { CaptureTestPattern.shared.show(on: displayID) } }
}

@_cdecl("wp_test_pattern_hide")
public func wp_test_pattern_hide(_ displayID: UInt32) {
    DispatchQueue.main.async { MainActor.assumeIsolated { CaptureTestPattern.shared.hide(on: displayID) } }
}

/// Called periodically by the daemon's main-thread event loop while the pattern
/// is enabled, so AppKit timers and drawing continue without a separate NSApp.run().
@_cdecl("wp_test_pattern_pump")
public func wp_test_pattern_pump() {
    MainActor.assumeIsolated {
        let app = NSApplication.shared
        CaptureTestPattern.shared.tick()
        while let event = app.nextEvent(matching: .any, until: Date(), inMode: .default, dequeue: true) {
            app.sendEvent(event)
        }
        app.updateWindows()
    }
}

/// Blocking C entry points must be called from a non-main thread. Async framework
/// work runs in detached tasks and reports completion through a semaphore. Frame
/// storage is borrowed only for the duration of `callback`; callers must copy it.
@_cdecl("wp_capture_start")
public func wp_capture_start(
    _ displayID: UInt32, _ width: UInt32, _ height: UInt32, _ hz: UInt32,
    _ bitrateBps: UInt32, _ callback: WPFrameCallback?, _ context: UnsafeMutableRawPointer?
) -> UInt64 {
    guard width > 0, height > 0, hz > 0, let callback else { return 0 }
    let callbackBox = CallbackBox(callback, context)
    let encoder = CaptureEncoder(displayID: displayID, width: Int(width), height: Int(height), hz: Double(hz), bitrate: Int(bitrateBps))
    let semaphore = DispatchSemaphore(value: 0)
    let result = StartupResult()
    Task.detached {
        do {
            try await encoder.start { frame in
                frame.annexB.withUnsafeBytes { raw in
                    callbackBox.callback(callbackBox.context, raw.bindMemory(to: UInt8.self).baseAddress, raw.count,
                             frame.isKeyframe ? 1 : 0, frame.ptsUs, frame.encodeLatencyUs)
                }
            }
            result.set(true)
        } catch {
            fputs("wp_capture_start: \(error)\n", stderr)
        }
        semaphore.signal()
    }
    semaphore.wait()
    guard result.get() else { return 0 }
    return CaptureRegistry.shared.insert(encoder)
}

@_cdecl("wp_capture_force_keyframe")
public func wp_capture_force_keyframe(_ handle: UInt64) {
    CaptureRegistry.shared.get(handle)?.encoder.forceKeyframe()
}

@_cdecl("wp_capture_stop")
public func wp_capture_stop(_ handle: UInt64) {
    guard let capture = CaptureRegistry.shared.remove(handle) else { return }
    let semaphore = DispatchSemaphore(value: 0)
    Task.detached { await capture.encoder.stop(); semaphore.signal() }
    semaphore.wait()
}
