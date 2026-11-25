import Foundation
import Darwin
import WingpaneBridge

private enum Edge: String {
    case left, right, top, bottom
    var code: UInt8 {
        switch self {
        case .left: return 0
        case .right: return 1
        case .top: return 2
        case .bottom: return 3
        }
    }
}

private struct DriverState {
    let edge: Edge
    let minX: Double
    let minY: Double
    let maxX: Double
    let maxY: Double
    var entered = false
}

private final class SelfTestDriver: @unchecked Sendable {
    static let shared = SelfTestDriver()
    private let lock = NSLock()
    private var state: DriverState?

    func configure(_ state: DriverState) {
        lock.lock(); self.state = state; lock.unlock()
    }

    func handle(_ event: WPUcEvent) {
        let line = "UC event kind=\(event.kind) reason=\(event.reason) x=\(event.x) y=\(event.y) dx=\(event.dx) dy=\(event.dy) key=\(event.keycode) down=\(event.down) flags=\(event.flags)\n"
        FileHandle.standardError.write(Data(line.utf8))
        guard event.kind == 0 else { return }
        lock.lock(); defer { lock.unlock() }
        guard var state, !state.entered else { return }
        let crossed: Bool
        switch state.edge {
        case .left: crossed = event.x <= state.minX + 1
        case .right: crossed = event.x >= state.maxX - 1
        case .top: crossed = event.y <= state.minY + 1
        case .bottom: crossed = event.y >= state.maxY - 1
        }
        if crossed {
            state.entered = true
            self.state = state
            wp_uc_set_inside(true, state.edge.code, 0.5)
        }
    }

    static let onEvent: WPUcCallback = { _, eventPointer in
        guard let eventPointer else { return }
        SelfTestDriver.shared.handle(eventPointer.assumingMemoryBound(to: WPUcEvent.self).pointee)
    }
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("wp-uc-selftest: \(message)\n".utf8))
    exit(EXIT_FAILURE)
}

guard CommandLine.arguments.count == 2, let edge = Edge(rawValue: CommandLine.arguments[1]) else {
    fail("usage: wp-uc-selftest <left|right|top|bottom>")
}
guard wp_uc_available() else { fail("Accessibility permission or HID event tap unavailable") }
var rects = [Double](repeating: 0, count: 64)
let displayCount = rects.withUnsafeMutableBufferPointer { buffer in
    wp_uc_display_rects(buffer.baseAddress, UInt32(buffer.count / 4))
}
guard displayCount > 0 else { fail("could not read active display bounds") }
let bounds = Array(rects.prefix(4))
SelfTestDriver.shared.configure(DriverState(edge: edge, minX: bounds[0], minY: bounds[1],
                                            maxX: bounds[0] + bounds[2], maxY: bounds[1] + bounds[3]))
guard wp_uc_start(SelfTestDriver.onEvent, nil) else { fail("could not start HID event tap") }
FileHandle.standardError.write(Data("UC self-test armed on \(edge.rawValue); auto-release in 15 seconds\n".utf8))
let deadline = ProcessInfo.processInfo.systemUptime + 15
while ProcessInfo.processInfo.systemUptime < deadline {
    wp_uc_heartbeat()
    Thread.sleep(forTimeInterval: 0.25)
}
wp_uc_release()
wp_uc_stop()
