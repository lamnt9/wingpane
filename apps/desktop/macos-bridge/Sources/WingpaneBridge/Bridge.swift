import Foundation
import Darwin
import AppKit
import ApplicationServices
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

@_cdecl("wp_screen_capture_trusted")
public func wp_screen_capture_trusted() -> Bool { CGPreflightScreenCaptureAccess() }

@_cdecl("wp_input_trusted")
public func wp_input_trusted() -> Bool { AXIsProcessTrusted() }

@_cdecl("wp_host_time_us")
public func wp_host_time_us() -> UInt64 {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    return mach_absolute_time() &* UInt64(info.numer) / UInt64(info.denom) / 1_000
}

@_cdecl("wp_input_release_all")
public func wp_input_release_all() {
    PointerState.shared.releaseAll()
    PenState.shared.releaseAll()
}

@_cdecl("wp_input_pointer")
public func wp_input_pointer(_ displayID: UInt32, _ x: Double, _ y: Double, _ phase: UInt8) {
    PointerState.shared.post(displayID: displayID, x: x, y: y, phase: phase)
}

@_cdecl("wp_input_pen")
public func wp_input_pen(_ displayID: UInt32, _ x: Double, _ y: Double, _ pressure: Double,
                         _ tiltX: Double, _ tiltY: Double, _ buttons: UInt8, _ flags: UInt8) {
    PenState.shared.post(displayID: displayID, x: x, y: y, pressure: pressure,
                         tiltX: tiltX, tiltY: tiltY, buttons: buttons, flags: flags)
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
    // The daemon's main thread runs the tokio runtime, not a CFRunLoop, so blocks posted to
    // DispatchQueue.main (show/hide) only execute when this pump spins the run loop. It must
    // therefore run INLINE when called on the main thread; dispatching it would never run.
    let body = {
        MainActor.assumeIsolated {
            let app = NSApplication.shared
            // nextEvent spins the main run loop, which also drains DispatchQueue.main.
            while let event = app.nextEvent(matching: .any, until: Date(), inMode: .default, dequeue: true) {
                app.sendEvent(event)
            }
            CaptureTestPattern.shared.tick()
            app.updateWindows()
        }
    }
    if Thread.isMainThread { body() } else { DispatchQueue.main.async(execute: body) }
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

@_cdecl("wp_capture_frames_captured")
public func wp_capture_frames_captured(_ handle: UInt64) -> UInt64 {
    UInt64(CaptureRegistry.shared.get(handle)?.encoder.framesCaptured ?? 0)
}
