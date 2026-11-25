import Foundation

/// Left-side NX modifier bits used by the daemon's macOS flag converter.
public enum UcModifier: UInt64, Sendable {
    case control = 0x0001
    case shift = 0x0002
    case alternate = 0x0020
    case command = 0x0008
}

public enum UcHotkey {
    /// Escape (0x35) with Control and Option, allowing extra unrelated flags
    /// (such as Caps Lock) while requiring both safety modifiers.
    public static func matches(keycode: UInt16, flags: UInt64) -> Bool {
        let required = (UInt64(1) << 18) | (UInt64(1) << 19) // CGEvent maskControl | maskAlternate
        return keycode == 0x35 && flags & required == required
    }
}

/// Stable C-compatible record emitted from the event tap. Keep this layout in
/// sync with WPUcEvent in docs/07-universal-control-plan.md.
@frozen public struct UcEventRecord: Sendable {
    public var kind: UInt8
    public var button: UInt8
    public var phase: UInt8
    public var reason: UInt8
    public var keycode: UInt16
    public var down: UInt8
    public var autorepeat: UInt8
    public var flags: UInt64
    public var x: Double
    public var y: Double
    public var dx: Double
    public var dy: Double

    public init(kind: UInt8 = 0, button: UInt8 = 0, phase: UInt8 = 0, reason: UInt8 = 0,
                keycode: UInt16 = 0, down: UInt8 = 0, autorepeat: UInt8 = 0,
                flags: UInt64 = 0, x: Double = 0, y: Double = 0,
                dx: Double = 0, dy: Double = 0) {
        self.kind = kind; self.button = button; self.phase = phase; self.reason = reason
        self.keycode = keycode; self.down = down; self.autorepeat = autorepeat
        self.flags = flags; self.x = x; self.y = y; self.dx = dx; self.dy = dy
    }
}

/// Convert aggregate Quartz flags to the left-side NX device-dependent bits
/// consumed by the daemon. Quartz does not expose left/right modifier identity.
public func ucMacDeviceFlags(_ flags: UInt64) -> UInt64 {
    var result: UInt64 = 0
    if flags & (1 << 18) != 0 { result |= 0x0001 } // Control
    if flags & (1 << 17) != 0 { result |= 0x0002 } // Shift
    if flags & (1 << 19) != 0 { result |= 0x0020 } // Option
    if flags & (1 << 20) != 0 { result |= 0x0008 } // Command
    return result
}
