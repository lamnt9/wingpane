import Foundation
import CoreGraphics
import ApplicationServices
import Carbon
import Darwin
import WingpaneUcSupport

public typealias WPUcEvent = UcEventRecord
public typealias WPUcCallback = @convention(c) (UnsafeMutableRawPointer?, UnsafeRawPointer?) -> Void

private enum UcExitReason: UInt8 { case hotkey = 1, watchdog = 2, tapDisabled = 3, secureInput = 4, macLocked = 5 }

/// Owns the HID tap and its run loop. The tap is created and serviced on its own
/// thread because the daemon's Tokio thread does not run a CFRunLoop.
final class UcTap: @unchecked Sendable {
    static let shared = UcTap()
    private let lock = NSRecursiveLock()
    private var thread: Thread?
    private var runLoop: CFRunLoop?
    private var source: CFMachPort?
    private var timer: CFRunLoopTimer?
    private var callback: WPUcCallback?
    private var context: UnsafeMutableRawPointer?
    private var inside = false
    private var edge: UInt8 = 1
    private var along = 0.5
    private var lastHeartbeat = ProcessInfo.processInfo.systemUptime
    private var tapHealthy = false
    private var stopped = true
    private var cursorHidden = false

    func available() -> Bool {
        guard AXIsProcessTrusted() else { return false }
        return makeTap() != nil
    }

    func start(_ callback: @escaping WPUcCallback, context: UnsafeMutableRawPointer?) -> Bool {
        lock.lock()
        if !stopped { lock.unlock(); return true }
        guard AXIsProcessTrusted() else { lock.unlock(); return false }
        self.callback = callback; self.context = context; stopped = false
        lastHeartbeat = ProcessInfo.processInfo.systemUptime
        lock.unlock()

        let ready = DispatchSemaphore(value: 0)
        let worker = Thread { [weak self] in self?.run(ready: ready) }
        worker.name = "io.wingpane.uc-event-tap"
        lock.lock(); thread = worker; lock.unlock()
        worker.start()
        ready.wait()
        lock.lock(); defer { lock.unlock() }
        return source != nil && tapHealthy
    }

    func setInside(_ value: Bool, edge: UInt8, along: Double) {
        lock.lock(); defer { lock.unlock() }
        self.edge = edge; self.along = min(max(along, 0), 1)
        guard inside != value else { return }
        if value { enterLocked() } else { exitLocked(reason: nil) }
    }

    func heartbeat() { lock.lock(); lastHeartbeat = ProcessInfo.processInfo.systemUptime; lock.unlock() }

    func release() {
        lock.lock(); defer { lock.unlock() }
        if inside { exitLocked(reason: .watchdog) }
        restoreCursorLocked()
    }

    func stop() {
        lock.lock()
        if stopped { lock.unlock(); return }
        if inside { exitLocked(reason: .watchdog) }
        stopped = true
        let loop = runLoop
        lock.unlock()
        if let loop { CFRunLoopStop(loop); CFRunLoopWakeUp(loop) }
        lock.lock(); defer { lock.unlock() }
        source = nil; timer = nil; runLoop = nil; thread = nil
        callback = nil; context = nil; tapHealthy = false
        restoreCursorLocked()
    }

    private func run(ready: DispatchSemaphore) {
        let loop = CFRunLoopGetCurrent()
        lock.lock(); runLoop = loop; lock.unlock()
        guard let tap = makeTap() else {
            startupFailed(); ready.signal(); return
        }
        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        guard let source else { startupFailed(); ready.signal(); return }
        CFRunLoopAddSource(loop, source, .commonModes)
        let timer = CFRunLoopTimerCreateWithHandler(kCFAllocatorDefault, CFAbsoluteTimeGetCurrent() + 0.25,
                                                    0.25, 0, 0) { [weak self] _ in self?.tick() }
        if let timer { CFRunLoopAddTimer(loop, timer, .commonModes) }
        lock.lock(); self.source = tap; self.timer = timer; tapHealthy = true; lock.unlock()
        CGEvent.tapEnable(tap: tap, enable: true)
        lock.lock(); emitStatus(secure: IsSecureEventInputEnabled()); lock.unlock()
        ready.signal()
        CFRunLoopRun()
        lock.lock(); tapHealthy = false; lock.unlock()
    }

    private func makeTap() -> CFMachPort? {
        let types: [CGEventType] = [
            .mouseMoved, .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp,
            .otherMouseDown, .otherMouseUp, .leftMouseDragged, .rightMouseDragged,
            .otherMouseDragged, .scrollWheel, .keyDown, .keyUp, .flagsChanged,
        ]
        var mask: UInt64 = 0
        for type in types { mask |= UInt64(1) << UInt64(type.rawValue) }
        return CGEvent.tapCreate(tap: .cghidEventTap, place: .headInsertEventTap, options: .defaultTap,
                                 eventsOfInterest: CGEventMask(mask), callback: ucTapCallback,
                                 userInfo: Unmanaged.passUnretained(self).toOpaque())
    }

    private func startupFailed() {
        lock.lock(); defer { lock.unlock() }
        stopped = true; tapHealthy = false; callback = nil; context = nil
        runLoop = nil; source = nil; timer = nil; thread = nil
    }

    fileprivate func handle(_ proxy: CGEventTapProxy, type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        lock.lock(); defer { lock.unlock() }
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let source { CGEvent.tapEnable(tap: source, enable: true) }
            if inside { exitLocked(reason: .tapDisabled) }
            tapHealthy = source != nil
            emitStatus(secure: IsSecureEventInputEnabled())
            return Unmanaged.passUnretained(event)
        }
        guard !stopped else { return Unmanaged.passUnretained(event) }
        let flags = event.flags.rawValue
        if type == .keyDown, UcHotkey.matches(keycode: UInt16(event.getIntegerValueField(.keyboardEventKeycode)), flags: flags) {
            if inside { exitLocked(reason: .hotkey); return nil }
            return Unmanaged.passUnretained(event)
        }
        if type == .keyDown || type == .keyUp || type == .flagsChanged {
            if IsSecureEventInputEnabled() {
                let wasInside = inside
                if wasInside { exitLocked(reason: .secureInput) }
                emitStatus(secure: true)
                return wasInside ? nil : Unmanaged.passUnretained(event)
            }
        }
        guard inside else {
            if type == .mouseMoved || type == .leftMouseDragged || type == .rightMouseDragged || type == .otherMouseDragged {
                emit(UcEventRecord(kind: 0, flags: flags, x: event.location.x, y: event.location.y,
                                   dx: Double(event.getIntegerValueField(.mouseEventDeltaX)),
                                   dy: Double(event.getIntegerValueField(.mouseEventDeltaY))))
            }
            return Unmanaged.passUnretained(event)
        }
        // Never leak input into the Mac while control is inside Android.
        if event.getIntegerValueField(.eventSourceStateID) != Int64(CGEventSourceStateID.hidSystemState.rawValue) {
            return nil
        }
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            emit(UcEventRecord(kind: 1, flags: flags,
                               dx: Double(event.getIntegerValueField(.mouseEventDeltaX)),
                               dy: Double(event.getIntegerValueField(.mouseEventDeltaY))))
        case .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp:
            let button = UInt8(clamping: event.getIntegerValueField(.mouseEventButtonNumber))
            emit(UcEventRecord(kind: 2, button: button, down: (type == .leftMouseDown || type == .rightMouseDown || type == .otherMouseDown) ? 1 : 0, flags: flags))
        case .scrollWheel:
            let continuous = event.getIntegerValueField(.scrollWheelEventIsContinuous) != 0
            let scale = continuous ? 1.0 : 16.0
            let dx = continuous
                ? Double(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis2))
                : Double(event.getIntegerValueField(.scrollWheelEventDeltaAxis2)) * scale
            let dy = continuous
                ? Double(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis1))
                : Double(event.getIntegerValueField(.scrollWheelEventDeltaAxis1)) * scale
            let phase = event.getIntegerValueField(.scrollWheelEventScrollPhase)
            let momentum = phase == 0 ? event.getIntegerValueField(.scrollWheelEventMomentumPhase) : phase
            emit(UcEventRecord(kind: 3, phase: UInt8(truncatingIfNeeded: momentum), dx: dx, dy: dy))
        case .keyDown, .keyUp:
            emit(UcEventRecord(kind: 4, keycode: UInt16(event.getIntegerValueField(.keyboardEventKeycode)),
                               down: type == .keyDown ? 1 : 0, autorepeat: event.getIntegerValueField(.keyboardEventAutorepeat) != 0 ? 1 : 0,
                               flags: ucMacDeviceFlags(flags)))
        case .flagsChanged: emit(UcEventRecord(kind: 5, flags: ucMacDeviceFlags(flags)))
        default: break
        }
        return nil
    }

    private func tick() {
        lock.lock(); defer { lock.unlock() }
        guard !stopped else { return }
        let secure = IsSecureEventInputEnabled()
        if secure { if inside { exitLocked(reason: .secureInput) }; emitStatus(secure: true) }
        if sessionLocked(), inside { exitLocked(reason: .macLocked) }
        if inside && ProcessInfo.processInfo.systemUptime - lastHeartbeat > 1 { exitLocked(reason: .watchdog) }
    }

    private func sessionLocked() -> Bool {
        guard let info = CGSessionCopyCurrentDictionary() as? [String: Any] else { return false }
        return (info["CGSSessionScreenIsLocked"] as? Bool == true) || (info["kCGSessionOnConsoleKey"] as? Bool == false)
    }

    private func enterLocked() {
        if IsSecureEventInputEnabled() {
            emitStatus(secure: true)
            emit(UcEventRecord(kind: 7, reason: UcExitReason.secureInput.rawValue))
            return
        }
        if sessionLocked() {
            emit(UcEventRecord(kind: 7, reason: UcExitReason.macLocked.rawValue))
            return
        }
        inside = true
        let bounds = CGDisplayBounds(CGMainDisplayID())
        CGWarpMouseCursorPosition(CGPoint(x: bounds.midX, y: bounds.midY))
        CGAssociateMouseAndMouseCursorPosition(0)
        hideCursorLocked()
        emit(UcEventRecord(kind: 6))
    }

    private func exitLocked(reason: UcExitReason?) {
        guard inside else { restoreCursorLocked(); return }
        inside = false
        let bounds = CGDisplayBounds(CGMainDisplayID())
        let position = CGFloat(along)
        let point: CGPoint
        switch edge {
        case 0: point = CGPoint(x: bounds.minX, y: bounds.minY + position * max(bounds.height - 1, 0))
        case 1: point = CGPoint(x: bounds.maxX - 1, y: bounds.minY + position * max(bounds.height - 1, 0))
        case 2: point = CGPoint(x: bounds.minX + position * max(bounds.width - 1, 0), y: bounds.minY)
        default: point = CGPoint(x: bounds.minX + position * max(bounds.width - 1, 0), y: bounds.maxY - 1)
        }
        showCursorLocked()
        CGWarpMouseCursorPosition(point)
        CGAssociateMouseAndMouseCursorPosition(1)
        emit(UcEventRecord(kind: 7, reason: reason?.rawValue ?? 0))
    }

    private func hideCursorLocked() {
        CGDisplayHideCursor(CGMainDisplayID())
        cursorHidden = true
        // Private API is intentionally looked up dynamically. Its absence is a
        // supported fallback: the cursor remains at the warped center.
        typealias DefaultConnection = @convention(c) () -> Int32
        typealias MainConnection = @convention(c) () -> Int32
        typealias SetProperty = @convention(c) (Int32, Int32, CFString, CFTypeRef) -> Int32
        guard let defaultSymbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "_CGSDefaultConnection"),
              let mainSymbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "CGSMainConnectionID"),
              let propertySymbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "CGSSetConnectionProperty") else { return }
        let defaultConnection = unsafeBitCast(defaultSymbol, to: DefaultConnection.self)
        let mainConnection = unsafeBitCast(mainSymbol, to: MainConnection.self)
        let setProperty = unsafeBitCast(propertySymbol, to: SetProperty.self)
        _ = setProperty(defaultConnection(), mainConnection(), "SetsCursorInBackground" as CFString, kCFBooleanTrue)
    }

    private func restoreCursorLocked() {
        showCursorLocked()
        CGAssociateMouseAndMouseCursorPosition(1)
    }

    private func showCursorLocked() {
        if cursorHidden { CGDisplayShowCursor(CGMainDisplayID()); cursorHidden = false }
    }

    private func emitStatus(secure: Bool) {
        emit(UcEventRecord(kind: 8, down: secure ? 1 : 0, autorepeat: tapHealthy ? 1 : 0))
    }

    private func emit(_ record: UcEventRecord) {
        guard let callback else { return }
        var copy = record
        withUnsafePointer(to: &copy) { callback(context, UnsafeRawPointer($0)) }
    }
}

private func ucTapCallback(proxy: CGEventTapProxy, type: CGEventType, event: CGEvent,
                           userInfo: UnsafeMutableRawPointer?) -> Unmanaged<CGEvent>? {
    guard let userInfo else { return Unmanaged.passUnretained(event) }
    return Unmanaged<UcTap>.fromOpaque(userInfo).takeUnretainedValue().handle(proxy, type: type, event: event)
}

@_cdecl("wp_uc_display_rects")
public func wp_uc_display_rects(_ out: UnsafeMutablePointer<Double>?, _ maxCount: UInt32) -> UInt32 {
    guard let out, maxCount > 0 else { return 0 }
    var count: UInt32 = 0
    var ids = [CGDirectDisplayID](repeating: 0, count: Int(maxCount))
    guard CGGetActiveDisplayList(maxCount, &ids, &count) == .success else { return 0 }
    let main = CGMainDisplayID()
    let ordered = ids.prefix(Int(count)).sorted { lhs, rhs in
        if lhs == main { return true }
        if rhs == main { return false }
        return lhs < rhs
    }
    for (index, displayID) in ordered.enumerated() {
        let bounds = CGDisplayBounds(displayID)
        let offset = index * 4
        out[offset] = bounds.minX; out[offset + 1] = bounds.minY
        out[offset + 2] = bounds.width; out[offset + 3] = bounds.height
    }
    return UInt32(ordered.count)
}
