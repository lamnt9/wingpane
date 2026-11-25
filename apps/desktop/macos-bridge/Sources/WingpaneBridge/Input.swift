import Foundation
import CoreGraphics
import ApplicationServices

final class PointerState: @unchecked Sendable {
    static let shared = PointerState()
    private let lock = NSLock()
    private var buttonDown = false
    private var lastLocation = CGPoint.zero
    private var lastClickAt = Date.distantPast
    private var clickCount = 0
    private(set) var droppedUntrusted: UInt64 = 0

    func releaseAll() {
        lock.lock(); defer { lock.unlock() }
        guard buttonDown else { return }
        if AXIsProcessTrusted() { postPointerEvent(.leftMouseUp, at: lastLocation) }
        else { droppedUntrusted &+= 1 }
        buttonDown = false
    }
    func relative(_ dx: Double, _ dy: Double) {
        lock.lock(); defer { lock.unlock() }
        guard AXIsProcessTrusted(), let current = CGEvent(source: nil)?.location else { return }
        let screens = (0..<16).map { UInt32($0) }
        var count: UInt32 = 0
        var ids = [CGDirectDisplayID](repeating: 0, count: screens.count)
        guard CGGetActiveDisplayList(UInt32(ids.count), &ids, &count) == .success else { return }
        let bounds = ids.prefix(Int(count)).map { CGDisplayBounds($0) }
        guard let union = bounds.first.map({ first in bounds.dropFirst().reduce(first) { $0.union($1) } }) else { return }
        let point = CGPoint(x: min(max(current.x + dx, union.minX), union.maxX - 1), y: min(max(current.y + dy, union.minY), union.maxY - 1))
        let type: CGEventType = buttonDown ? .leftMouseDragged : .mouseMoved
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: .left) else { return }
        event.setIntegerValueField(CGEventField.mouseEventDeltaX, value: Int64(dx.rounded()))
        event.setIntegerValueField(CGEventField.mouseEventDeltaY, value: Int64(dy.rounded()))
        event.post(tap: CGEventTapLocation.cghidEventTap)
        lastLocation = point
    }
    func button(_ button: UInt8, _ down: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard AXIsProcessTrusted(), let point = CGEvent(source: nil)?.location else { return }
        let type: CGEventType = button == 1 ? (down ? .rightMouseDown : .rightMouseUp) : (down ? .leftMouseDown : .leftMouseUp)
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: button == 1 ? .right : .left) else { return }
        if down {
            let now = Date()
            clickCount = now.timeIntervalSince(lastClickAt) <= 0.5 ? min(clickCount + 1, 2) : 1
            lastClickAt = now
        }
        event.setIntegerValueField(.mouseEventClickState, value: Int64(max(clickCount, 1)))
        event.post(tap: .cghidEventTap)
        if button == 0 { buttonDown = down; lastLocation = point }
    }
    func scroll(_ dx: Double, _ dy: Double) {
        guard AXIsProcessTrusted(), let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: Int32(dy.rounded()), wheel2: Int32(dx.rounded()), wheel3: 0) else { return }
        event.post(tap: .cghidEventTap)
    }

    func post(displayID: UInt32, x: Double, y: Double, phase: UInt8) {
        let bounds = CGDisplayBounds(CGDirectDisplayID(displayID))
        guard bounds.width > 0, bounds.height > 0 else { return }
        let point = CGPoint(x: bounds.minX + min(max(x, 0), 1) * max(bounds.width - 1, 0),
                            y: bounds.minY + min(max(y, 0), 1) * max(bounds.height - 1, 0))
        lock.lock(); defer { lock.unlock() }
        guard AXIsProcessTrusted() else { droppedUntrusted &+= 1; return }
        lastLocation = point
        switch phase {
        case 0:
            if buttonDown { postPointerEvent(.leftMouseUp, at: point) }
            postPointerEvent(.leftMouseDown, at: point)
            buttonDown = true
        case 1:
            postPointerEvent(buttonDown ? .leftMouseDragged : .mouseMoved, at: point)
        case 2, 3:
            if buttonDown { postPointerEvent(.leftMouseUp, at: point); buttonDown = false }
        default: break
        }
    }
}

final class KeyboardState: @unchecked Sendable {
    static let shared = KeyboardState()
    private let lock = NSLock()
    private var pressed: [UInt16: UInt8] = [:]
    private var activeModifiers: UInt8 = 0
    private let modifierKeycodes: [UInt16] = [0x3b, 0x38, 0x3a, 0x37, 0x3e, 0x3c, 0x3d, 0x36]
    func post(_ keycode: UInt16, _ down: Bool, _ modifiers: UInt8) {
        lock.lock(); defer { lock.unlock() }
        guard AXIsProcessTrusted() else { return }
        let effectiveModifiers = down ? modifiers : (pressed[keycode] ?? modifiers)
        syncModifiers(effectiveModifiers)
        guard let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keycode), keyDown: down) else { return }
        var flags: CGEventFlags = []
        if effectiveModifiers & 0x11 != 0 { flags.insert(.maskControl) }
        if effectiveModifiers & 0x22 != 0 { flags.insert(.maskShift) }
        if effectiveModifiers & 0x44 != 0 { flags.insert(.maskAlternate) }
        if effectiveModifiers & 0x88 != 0 { flags.insert(.maskCommand) }
        event.flags = flags
        event.post(tap: .cghidEventTap)
        if down {
            pressed[keycode] = effectiveModifiers
        } else {
            pressed.removeValue(forKey: keycode)
            syncModifiers(modifiers)
        }
    }
    func releaseAll() {
        lock.lock(); defer { lock.unlock() }
        for (key, modifiers) in pressed.sorted(by: { $0.key < $1.key }) {
            if let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(key), keyDown: false) {
                var flags: CGEventFlags=[]
                if modifiers & 1 != 0 { flags.insert(.maskControl) }; if modifiers & 2 != 0 { flags.insert(.maskShift) }
                if modifiers & 4 != 0 { flags.insert(.maskAlternate) }; if modifiers & 8 != 0 { flags.insert(.maskCommand) }
                event.flags=flags; event.post(tap: .cghidEventTap)
            }
        }
        pressed.removeAll()
        syncModifiers(0)
    }
    private func syncModifiers(_ next: UInt8) {
        let masks: [UInt8] = [1, 2, 4, 8, 16, 32, 64, 128]
        for index in masks.indices where (activeModifiers & masks[index] != 0) != (next & masks[index] != 0) {
            let isDown = next & masks[index] != 0
            if let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(modifierKeycodes[index]), keyDown: isDown) {
                event.type = .flagsChanged
                var flags: CGEventFlags = []
                if next & 0x11 != 0 { flags.insert(.maskControl) }
                if next & 0x22 != 0 { flags.insert(.maskShift) }
                if next & 0x44 != 0 { flags.insert(.maskAlternate) }
                if next & 0x88 != 0 { flags.insert(.maskCommand) }
                event.flags = flags
                event.post(tap: .cghidEventTap)
            }
        }
        activeModifiers = next
    }
}

/// Posts tablet-point events to the virtual display. The same stable device ID is
/// used for proximity and point events so drawing apps can associate the samples.
final class PenState: @unchecked Sendable {
    static let shared = PenState()
    private let lock = NSLock()
    private let deviceID: Int64 = 0x5750
    private var inProximity = false
    private var penDown = false
    private var rightDown = false
    private var lastLocation = CGPoint.zero

    func releaseAll() {
        lock.lock(); defer { lock.unlock() }
        if AXIsProcessTrusted() {
            if penDown { postMouse(.leftMouseUp, at: lastLocation, pressure: 0, tiltX: 0, tiltY: 0) }
            if rightDown { postMouse(.rightMouseUp, at: lastLocation, pressure: 0, tiltX: 0, tiltY: 0) }
            if inProximity { postProximity(enter: false, eraser: false) }
        }
        penDown = false; rightDown = false; inProximity = false
    }

    func post(displayID: UInt32, x: Double, y: Double, pressure: Double,
              tiltX: Double, tiltY: Double, buttons: UInt8, flags: UInt8) {
        let bounds = CGDisplayBounds(CGDirectDisplayID(displayID))
        guard bounds.width > 0, bounds.height > 0 else { return }
        let point = CGPoint(x: bounds.minX + min(max(x, 0), 1) * max(bounds.width - 1, 0),
                            y: bounds.minY + min(max(y, 0), 1) * max(bounds.height - 1, 0))
        let down = flags & 1 != 0
        let hover = flags & 2 != 0
        let exiting = flags & 4 != 0
        let eraser = buttons & 4 != 0
        lock.lock(); defer { lock.unlock() }
        guard AXIsProcessTrusted() else { return }
        lastLocation = point
        if exiting {
            finish(at: point)
            return
        }
        if !inProximity { postProximity(enter: true, eraser: eraser); inProximity = true }
        let p = hover ? 0 : min(max(pressure, 0), 1)
        let tx = min(max(tiltX / 90, -1), 1)
        let ty = min(max(tiltY / 90, -1), 1)
        let side = buttons & 3 != 0
        let secondaryDown = side
        if secondaryDown != rightDown {
            postMouse(secondaryDown ? .rightMouseDown : .rightMouseUp, at: point, pressure: p, tiltX: tx, tiltY: ty)
            rightDown = secondaryDown
        }
        let nextDown = down && !hover
        if nextDown != penDown {
            postMouse(nextDown ? .leftMouseDown : .leftMouseUp, at: point, pressure: p, tiltX: tx, tiltY: ty)
            penDown = nextDown
        } else {
            let type: CGEventType = penDown ? .leftMouseDragged : .mouseMoved
            postMouse(type, at: point, pressure: p, tiltX: tx, tiltY: ty)
        }
    }

    private func finish(at point: CGPoint) {
        if penDown { postMouse(.leftMouseUp, at: point, pressure: 0, tiltX: 0, tiltY: 0) }
        if rightDown { postMouse(.rightMouseUp, at: point, pressure: 0, tiltX: 0, tiltY: 0) }
        penDown = false; rightDown = false
        if inProximity { postProximity(enter: false, eraser: false); inProximity = false }
    }

    private func postProximity(enter: Bool, eraser: Bool) {
        guard let event = CGEvent(source: nil) else { return }
        event.type = .tabletProximity
        event.setIntegerValueField(.tabletProximityEventEnterProximity, value: enter ? 1 : 0)
        event.setIntegerValueField(.tabletProximityEventPointerType, value: eraser ? 3 : 1)
        event.setIntegerValueField(.tabletProximityEventDeviceID, value: deviceID)
        event.setIntegerValueField(.tabletProximityEventVendorID, value: 0x5750)
        event.setIntegerValueField(.tabletProximityEventTabletID, value: 1)
        event.setIntegerValueField(.tabletProximityEventCapabilityMask, value: 0x3f)
        event.post(tap: .cghidEventTap)
    }

    private func postMouse(_ type: CGEventType, at point: CGPoint, pressure: Double, tiltX: Double, tiltY: Double) {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type,
                                  mouseCursorPosition: point, mouseButton: type == .rightMouseDown || type == .rightMouseUp ? .right : .left) else { return }
        event.setIntegerValueField(.mouseEventSubtype, value: 1)
        event.setIntegerValueField(.tabletEventDeviceID, value: deviceID)
        event.setDoubleValueField(.tabletEventPointPressure, value: pressure)
        event.setDoubleValueField(.mouseEventPressure, value: pressure)
        event.setDoubleValueField(.tabletEventTiltX, value: tiltX)
        event.setDoubleValueField(.tabletEventTiltY, value: tiltY)
        event.post(tap: .cghidEventTap)
    }
}

private func postPointerEvent(_ type: CGEventType, at point: CGPoint) {
    guard AXIsProcessTrusted(), let event = CGEvent(mouseEventSource: nil, mouseType: type,
        mouseCursorPosition: point, mouseButton: .left) else { return }
    event.post(tap: .cghidEventTap)
}
