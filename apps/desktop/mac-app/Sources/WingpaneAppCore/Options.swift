import Foundation

public struct Options: Equatable, Codable, Sendable {
    public struct RestartRelevant: Equatable, Sendable {
        public let hidpi: Bool
        public let testPattern: Bool
        public let bitrateMbps: Int
    }

    public static let supportedBitrates: [Int] = [10, 20, 30]
    public static let defaultBitrateMbps: Int = 20

    public static let hidpiKey = "wingpane.options.hidpi"
    public static let testPatternKey = "wingpane.options.testPattern"
    public static let bitrateMbpsKey = "wingpane.options.bitrateMbps"

    public var hidpi: Bool
    public var testPattern: Bool
    public var bitrateMbps: Int {
        didSet {
            if !Self.supportedBitrates.contains(bitrateMbps) {
                bitrateMbps = Self.defaultBitrateMbps
            }
        }
    }

    public var restartRelevant: RestartRelevant {
        RestartRelevant(hidpi: hidpi, testPattern: testPattern, bitrateMbps: bitrateMbps)
    }

    public init(
        hidpi: Bool = true,
        testPattern: Bool = false,
        bitrateMbps: Int = Options.defaultBitrateMbps,
    ) {
        self.hidpi = hidpi
        self.testPattern = testPattern
        self.bitrateMbps = Self.supportedBitrates.contains(bitrateMbps)
            ? bitrateMbps
            : Self.defaultBitrateMbps
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

        return Options(
            hidpi: hidpi,
            testPattern: testPattern,
            bitrateMbps: bitrateMbps
        )
    }

    public func save(to defaults: UserDefaults = .standard) {
        defaults.set(hidpi, forKey: Self.hidpiKey)
        defaults.set(testPattern, forKey: Self.testPatternKey)
        defaults.set(bitrateMbps, forKey: Self.bitrateMbpsKey)
    }

    public func daemonArguments(serial: String? = nil) -> [String] {
        var args: [String] = [
            "--status-json",
            "--hidpi", hidpi ? "true" : "false",
            "--bitrate-mbps", "\(bitrateMbps)",
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
