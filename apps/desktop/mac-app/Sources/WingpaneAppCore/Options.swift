import Foundation

public enum UcEdge: String, Codable, Equatable, Sendable, CaseIterable {
    case left
    case right
    case top
    case bottom

    public var displayTitle: String {
        switch self {
        case .left:
            return L("Left", "Trái")
        case .right:
            return L("Right", "Phải")
        case .top:
            return L("Top", "Trên")
        case .bottom:
            return L("Bottom", "Dưới")
        }
    }
}

public struct Options: Equatable, Codable, Sendable {
    public struct RestartRelevant: Equatable, Sendable {
        public let hidpi: Bool
        public let testPattern: Bool
        public let bitrateMbps: Int
    }

    public static let supportedBitrates: [Int] = [10, 20, 30]
    public static let defaultBitrateMbps: Int = 20
    public static let defaultUcEdge: UcEdge = .right

    public static let hidpiKey = "wingpane.options.hidpi"
    public static let testPatternKey = "wingpane.options.testPattern"
    public static let bitrateMbpsKey = "wingpane.options.bitrateMbps"
    public static let ucEdgeKey = "wingpane.options.ucEdge"

    public var hidpi: Bool
    public var testPattern: Bool
    public var bitrateMbps: Int {
        didSet {
            if !Self.supportedBitrates.contains(bitrateMbps) {
                bitrateMbps = Self.defaultBitrateMbps
            }
        }
    }
    public var ucEdge: UcEdge

    public var restartRelevant: RestartRelevant {
        RestartRelevant(hidpi: hidpi, testPattern: testPattern, bitrateMbps: bitrateMbps)
    }

    public init(
        hidpi: Bool = true,
        testPattern: Bool = false,
        bitrateMbps: Int = Options.defaultBitrateMbps,
        ucEdge: UcEdge = Options.defaultUcEdge
    ) {
        self.hidpi = hidpi
        self.testPattern = testPattern
        self.bitrateMbps = Self.supportedBitrates.contains(bitrateMbps)
            ? bitrateMbps
            : Self.defaultBitrateMbps
        self.ucEdge = ucEdge
    }

    public static func load(from defaults: UserDefaults = .standard) -> Options {
        let hidpi: Bool
        if defaults.object(forKey: hidpiKey) != nil {
            hidpi = defaults.bool(forKey: hidpiKey)
        } else {
            hidpi = true
        }

        let testPattern: Bool
        if defaults.object(forKey: testPatternKey) != nil {
            testPattern = defaults.bool(forKey: testPatternKey)
        } else {
            testPattern = false
        }

        let bitrateMbps: Int
        if defaults.object(forKey: bitrateMbpsKey) != nil {
            let stored = defaults.integer(forKey: bitrateMbpsKey)
            bitrateMbps = supportedBitrates.contains(stored) ? stored : defaultBitrateMbps
        } else {
            bitrateMbps = defaultBitrateMbps
        }

        let ucEdge: UcEdge
        if let rawEdge = defaults.string(forKey: ucEdgeKey),
           let parsedEdge = UcEdge(rawValue: rawEdge) {
            ucEdge = parsedEdge
        } else {
            ucEdge = defaultUcEdge
        }

        return Options(
            hidpi: hidpi,
            testPattern: testPattern,
            bitrateMbps: bitrateMbps,
            ucEdge: ucEdge
        )
    }

    public func save(to defaults: UserDefaults = .standard) {
        defaults.set(hidpi, forKey: Self.hidpiKey)
        defaults.set(testPattern, forKey: Self.testPatternKey)
        defaults.set(bitrateMbps, forKey: Self.bitrateMbpsKey)
        defaults.set(ucEdge.rawValue, forKey: Self.ucEdgeKey)
    }

    public func daemonArguments(serial: String? = nil) -> [String] {
        var args: [String] = [
            "--status-json",
            "--hidpi", hidpi ? "true" : "false",
            "--bitrate-mbps", "\(bitrateMbps)",
            "--uc-edge", ucEdge.rawValue,
        ]
        if testPattern {
            args.append("--test-pattern")
        }
        if let serial = serial?.trimmingCharacters(in: .whitespacesAndNewlines), !serial.isEmpty {
            args.append(contentsOf: ["--serial", serial])
        }
        return args
    }
}
