import ApplicationServices
import CoreGraphics
import Foundation

public enum CursorSafety {
    /// Restores the macOS mouse cursor association, visibility, and warps it
    /// back to the center of the main display. Safe to call multiple times.
    public static func restore() {
        CGAssociateMouseAndMouseCursorPosition(1)
        let mainDisplay = CGMainDisplayID()
        CGDisplayShowCursor(mainDisplay)
        let bounds = CGDisplayBounds(mainDisplay)
        if bounds.width > 0, bounds.height > 0 {
            CGWarpMouseCursorPosition(CGPoint(x: bounds.midX, y: bounds.midY))
        }
    }
}
