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

private func postPointerEvent(_ type: CGEventType, at point: CGPoint) {
    guard AXIsProcessTrusted(), let event = CGEvent(mouseEventSource: nil, mouseType: type,
        mouseCursorPosition: point, mouseButton: .left) else { return }
    event.post(tap: .cghidEventTap)
}
