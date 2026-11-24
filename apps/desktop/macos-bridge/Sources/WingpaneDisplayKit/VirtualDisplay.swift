import CoreGraphics
import Foundation
import CGVirtualDisplayPrivate

public enum VirtualDisplayError: Error, CustomStringConvertible {
    case invalidDimensions
    case classUnavailable(String)
    case creationFailed
    case settingsRejected
    case displayNotOnline

    public var description: String {
        switch self {
        case .invalidDimensions:
            return "width and height must be positive and refreshHz must be finite and positive"
        case .classUnavailable(let name):
            return "private macOS class is unavailable: \(name)"
        case .creationFailed:
            return "CGVirtualDisplay initialization failed"
        case .settingsRejected:
            return "CGVirtualDisplay rejected the requested mode"
        case .displayNotOnline:
            return "CGVirtualDisplay was created but is not online"
        }
    }
}

public final class VirtualDisplay {
    private var virtualDisplay: CGVirtualDisplay?

    public let displayID: CGDirectDisplayID
    public let width: Int
    public let height: Int
    public let refreshHz: Double
    public let hiDPI: Bool
    public let name: String

    public init(width: Int, height: Int, refreshHz: Double = 60, hiDPI: Bool = false, name: String = "Wingpane") throws {
        guard width > 0, height > 0, refreshHz.isFinite, refreshHz > 0 else {
            throw VirtualDisplayError.invalidDimensions
        }
        guard NSClassFromString("CGVirtualDisplayDescriptor") != nil else {
            throw VirtualDisplayError.classUnavailable("CGVirtualDisplayDescriptor")
        }
        guard NSClassFromString("CGVirtualDisplayMode") != nil else {
            throw VirtualDisplayError.classUnavailable("CGVirtualDisplayMode")
        }
        guard NSClassFromString("CGVirtualDisplaySettings") != nil else {
            throw VirtualDisplayError.classUnavailable("CGVirtualDisplaySettings")
        }
        guard NSClassFromString("CGVirtualDisplay") != nil else {
            throw VirtualDisplayError.classUnavailable("CGVirtualDisplay")
        }

        self.width = width
        self.height = height
        self.refreshHz = refreshHz
        self.hiDPI = hiDPI
        self.name = name

        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.name = name
        descriptor.vendorID = 0x5750
        descriptor.productID = 0x0001
        descriptor.serialNum = UInt32.random(in: 1...UInt32.max)
        descriptor.sizeInMillimeters = CGSize(width: 600, height: 375)
        descriptor.maxPixelsWide = UInt(width)
        descriptor.maxPixelsHigh = UInt(height)

        guard let display = CGVirtualDisplay(descriptor: descriptor) else {
            throw VirtualDisplayError.creationFailed
        }

        let mode = CGVirtualDisplayMode(width: UInt(width), height: UInt(height), refreshRate: refreshHz)
        let settings = CGVirtualDisplaySettings()
        settings.modes = [mode]
        settings.hiDPI = hiDPI
        guard display.apply(settings) else {
            throw VirtualDisplayError.settingsRejected
        }
        guard display.displayID != 0 else {
            throw VirtualDisplayError.displayNotOnline
        }

        self.virtualDisplay = display
        self.displayID = display.displayID
    }

    public func close() {
        virtualDisplay = nil
    }

    deinit {
        close()
    }
}

@_cdecl("wp_vd_create")
public func wp_vd_create(
    _ width: UInt32,
    _ height: UInt32,
    _ refreshHz: Double,
    _ hiDPI: Bool,
    _ name: UnsafePointer<CChar>?
) -> UInt32 {
    let displayName = name.map { String(cString: $0) } ?? "Wingpane"
    do {
        let display = try VirtualDisplay(
            width: Int(width),
            height: Int(height),
            refreshHz: refreshHz,
            hiDPI: hiDPI,
            name: displayName
        )
        let id = display.displayID
        CDisplayRegistry.shared.insert(display, for: id)
        return id
    } catch {
        fputs("wp_vd_create: \(error)\n", stderr)
        return 0
    }
}

@_cdecl("wp_vd_destroy")
public func wp_vd_destroy(_ displayID: UInt32) {
    CDisplayRegistry.shared.removeValue(for: displayID)?.close()
}

private final class CDisplayRegistry: @unchecked Sendable {
    static let shared = CDisplayRegistry()
    private var displays: [UInt32: VirtualDisplay] = [:]
    private let lock = NSLock()

    func insert(_ display: VirtualDisplay, for id: UInt32) {
        lock.lock()
        displays[id] = display
        lock.unlock()
    }

    func removeValue(for id: UInt32) -> VirtualDisplay? {
        lock.lock()
        defer { lock.unlock() }
        return displays.removeValue(forKey: id)
    }
}
