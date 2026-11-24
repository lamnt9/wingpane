import Foundation

public struct AdbLocator: Sendable {
    public static let envVarName = "WINGPANE_ADB"
    public static let installHint = "Install Android platform-tools (e.g. brew install android-platform-tools)"

    private let environment: [String: String]
    private let homeDirectory: String
    private let fileExists: @Sendable (String) -> Bool

    public init(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        homeDirectory: String = FileManager.default.homeDirectoryForCurrentUser.path,
        fileExists: @escaping @Sendable (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) {
        self.environment = environment
        self.homeDirectory = homeDirectory
        self.fileExists = fileExists
    }

    /// Returns candidate paths in strict MA-3 order:
    /// 1. `$ANDROID_HOME/platform-tools/adb`
    /// 2. `~/Library/Android/sdk/platform-tools/adb`
    /// 3. `/opt/homebrew/bin/adb`
    /// 4. `/usr/local/bin/adb`
    public func candidatePaths() -> [String] {
        var paths: [String] = []
        if let rawAndroidHome = environment["ANDROID_HOME"]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !rawAndroidHome.isEmpty {
            let trimmedHome = rawAndroidHome.hasSuffix("/") ? String(rawAndroidHome.dropLast()) : rawAndroidHome
            paths.append("\(trimmedHome)/platform-tools/adb")
        }
        let trimmedUserHome = homeDirectory.hasSuffix("/") ? String(homeDirectory.dropLast()) : homeDirectory
        if !trimmedUserHome.isEmpty {
            paths.append("\(trimmedUserHome)/Library/Android/sdk/platform-tools/adb")
        }
        paths.append("/opt/homebrew/bin/adb")
        paths.append("/usr/local/bin/adb")
        return paths
    }

    public func locate() -> String? {
        candidatePaths().first(where: { fileExists($0) })
    }

    public static func openPhoneAppArguments(serial: String?) -> [String] {
        if let serial = serial?.trimmingCharacters(in: .whitespacesAndNewlines), !serial.isEmpty {
            return ["-s", serial, "shell", "am", "start", "-n", "io.wingpane/.MainActivity"]
        }
        return ["shell", "am", "start", "-n", "io.wingpane/.MainActivity"]
    }
}
