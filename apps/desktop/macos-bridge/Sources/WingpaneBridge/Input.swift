import Foundation
import CoreGraphics
import ApplicationServices

final class PointerState: @unchecked Sendable {
    static let shared = PointerState()
    private let lock = NSLock()
    private var buttonDown = false
    private var lastLocation = CGPoint.zero
    private(set) var droppedUntrusted: UInt64 = 0

    func releaseAll() {
        lock.lock(); defer { lock.unlock() }
        guard buttonDown else { return }
        if AXIsProcessTrusted() { postPointerEvent(.leftMouseUp, at: lastLocation) }
        else { droppedUntrusted &+= 1 }
        buttonDown = false
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
