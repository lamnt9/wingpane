import ApplicationServices
import Combine
import CoreGraphics
import Darwin
import Foundation

public enum DaemonCommand: Equatable, Sendable, Encodable {
    case ucEnable(Bool)
    case ucEdge(UcEdge)
    case ucReturn

    private enum CodingKeys: String, CodingKey {
        case cmd
        case enabled
        case edge
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .ucEnable(let enabled):
            try container.encode("uc_enable", forKey: .cmd)
            try container.encode(enabled, forKey: .enabled)
        case .ucEdge(let edge):
            try container.encode("uc_edge", forKey: .cmd)
            try container.encode(edge.rawValue, forKey: .edge)
        case .ucReturn:
            try container.encode("uc_return", forKey: .cmd)
        }
    }

    /// Encodes the command as a single JSON line ending with `\n`.
    public func encodeJsonLine() -> String {
        switch self {
        case .ucEnable(let enabled):
            return #"{"cmd":"uc_enable","enabled":\#(enabled ? "true" : "false")}"# + "\n"
        case .ucEdge(let edge):
            return #"{"cmd":"uc_edge","edge":"\#(edge.rawValue)"}"# + "\n"
        case .ucReturn:
            return #"{"cmd":"uc_return"}"# + "\n"
        }
    }
}

public final class DaemonController: ObservableObject, @unchecked Sendable {
    public let statusModel: StatusModel

    private let lock = NSLock()
    private let adbLocator: AdbLocator
    private let userDefaults: UserDefaults
    private let daemonExecutableOverride: URL?
    private let baseEnvironment: [String: String]
    private let stopGracePeriod: TimeInterval
    private let watchdogTimeout: TimeInterval
    private let cursorRestore: @Sendable () -> Void

    private var process: Process?
    private var stdinHandle: FileHandle?
    private var readerDoneSemaphore: DispatchSemaphore?
    private var watchdogTimer: DispatchSourceTimer?
    private var lastStdoutAt: Date = Date()
    private var isStopping: Bool = false

    private var _options: Options
    private var _selectedSerial: String?
    private var _restartCount: Int = 0

    public init(
        statusModel: StatusModel = StatusModel(),
        adbLocator: AdbLocator = AdbLocator(),
        userDefaults: UserDefaults = .standard,
        daemonExecutableURL: URL? = nil,
        environment: [String: String] = ProcessInfo.processInfo.environment,
        stopGracePeriod: TimeInterval = 3.0,
        watchdogTimeout: TimeInterval = 3.0,
        cursorRestore: @escaping @Sendable () -> Void = { CursorSafety.restore() }
    ) {
        self.statusModel = statusModel
        self.adbLocator = adbLocator
        self.userDefaults = userDefaults
        self.daemonExecutableOverride = daemonExecutableURL
        self.baseEnvironment = environment
        self.stopGracePeriod = stopGracePeriod
        self.watchdogTimeout = watchdogTimeout
        self.cursorRestore = cursorRestore
        self._options = Options.load(from: userDefaults)
    }

    public var options: Options {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _options
        }
        set {
            setOptions(newValue)
        }
    }

    public var selectedSerial: String? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _selectedSerial
        }
        set {
            selectDevice(serial: newValue)
        }
    }

    public var isRunning: Bool {
        lock.lock()
        let running = process?.isRunning ?? false
        lock.unlock()
        return running
    }

    public var restartCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _restartCount
    }

    public func setOptions(_ newOptions: Options) {
        var normalized = newOptions
        if !Options.supportedBitrates.contains(normalized.bitrateMbps) {
            normalized.bitrateMbps = Options.defaultBitrateMbps
        }

        lock.lock()
        let previous = _options
        _options = normalized
        let isProcRunning = (process?.isRunning ?? false)
        let shouldRestart = (previous.restartRelevant != normalized.restartRelevant) && isProcRunning
        let shouldSendEdge = !shouldRestart && (previous.ucEdge != normalized.ucEdge) && isProcRunning
        lock.unlock()

        normalized.save(to: userDefaults)
        notifyChange()

        if shouldRestart {
            restart()
        } else if shouldSendEdge {
            send(command: .ucEdge(normalized.ucEdge))
        }
    }

    @discardableResult
    public func send(command: DaemonCommand) -> Bool {
        let line = command.encodeJsonLine()
        guard let data = line.data(using: .utf8) else {
            return false
        }
        lock.lock()
        guard let proc = process, proc.isRunning, let handle = stdinHandle else {
            lock.unlock()
            return false
        }
        do {
            try handle.write(contentsOf: data)
            lock.unlock()
            return true
        } catch {
            lock.unlock()
            return false
        }
    }

    public func selectDevice(serial: String?) {
        let normalized = serial?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSerial = (normalized?.isEmpty == false) ? normalized : nil

        lock.lock()
        let changed = (_selectedSerial != cleanSerial)
        _selectedSerial = cleanSerial
        let shouldRestart = changed && (process?.isRunning ?? false)
        lock.unlock()

        notifyChange()

        if shouldRestart {
            restart()
        }
    }

    /// Locate `wingpaned` per Deliverable 1:
    /// 1. `Bundle.main.url(forResource: "wingpaned", withExtension: nil)`
    /// 2. Env `WINGPANE_DAEMON`
    /// 3. `../../../target/release/wingpaned` relative to package directory
    public static func locateDaemonExecutable(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment,
        packageDirectory: URL? = nil,
        fileExists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) -> URL? {
        if let bundledURL = bundle.url(forResource: "wingpaned", withExtension: nil),
           fileExists(bundledURL.path) {
            return bundledURL
        }

        if let envPath = environment["WINGPANE_DAEMON"]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !envPath.isEmpty,
           fileExists(envPath) {
            return URL(fileURLWithPath: envPath)
        }

        let pkgDir = packageDirectory ?? URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let relURL = pkgDir.appendingPathComponent("../../../target/release/wingpaned").standardizedFileURL
        if fileExists(relURL.path) {
            return relURL
        }

        let cwdRelURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("../../../target/release/wingpaned")
            .standardizedFileURL
        if fileExists(cwdRelURL.path) {
            return cwdRelURL
        }

        return nil
    }

    @discardableResult
    public func start() -> Bool {
        lock.lock()
        if let existing = process, existing.isRunning {
            lock.unlock()
            return true
        }
        let currentOptions = _options
        let currentSerial = _selectedSerial
        lock.unlock()

        let adbPath = adbLocator.locate()
        if adbPath == nil {
            statusModel.apply(.adbMissing)
        }

        guard let executableURL = daemonExecutableOverride ?? Self.locateDaemonExecutable(environment: baseEnvironment) else {
            if adbPath != nil {
                statusModel.apply(.error(message: "wingpaned binary not found"))
            }
            statusModel.setRunning(false)
            notifyChange()
            return false
        }

        let proc = Process()
        proc.executableURL = executableURL
        proc.arguments = currentOptions.daemonArguments(serial: currentSerial)

        var env = baseEnvironment
        if let adbPath {
            env[AdbLocator.envVarName] = adbPath
        }
        proc.environment = env

        let stdinPipe = Pipe()
        let stdoutPipe = Pipe()
        proc.standardInput = stdinPipe
        proc.standardOutput = stdoutPipe
        proc.standardError = Self.daemonLogHandle() ?? FileHandle.nullDevice

        let doneSemaphore = DispatchSemaphore(value: 0)

        lock.lock()
        self.process = proc
        self.stdinHandle = stdinPipe.fileHandleForWriting
        self.readerDoneSemaphore = doneSemaphore
        self.lastStdoutAt = Date()
        self.isStopping = false
        lock.unlock()

        proc.terminationHandler = { [weak self] terminatedProcess in
            guard let self else {
                CursorSafety.restore()
                return
            }
            self.cursorRestore()
            self.lock.lock()
            let wasCurrent = (self.process === terminatedProcess)
            let wasStopping = self.isStopping
            if wasCurrent {
                self.process = nil
                try? self.stdinHandle?.close()
                self.stdinHandle = nil
                self.watchdogTimer?.cancel()
                self.watchdogTimer = nil
            }
            self.lock.unlock()

            self.statusModel.setRunning(false)
            if wasStopping || self.statusModel.iconState != .error {
                if self.statusModel.lastEvent != .adbMissing {
                    self.statusModel.apply(.stopped)
                }
            }
            self.notifyChange()
        }

        do {
            try proc.run()
        } catch {
            lock.lock()
            self.process = nil
            try? self.stdinHandle?.close()
            self.stdinHandle = nil
            self.readerDoneSemaphore = nil
            lock.unlock()
            doneSemaphore.signal()
            statusModel.setRunning(false)
            statusModel.apply(.error(message: "Failed to launch wingpaned: \(error.localizedDescription)"))
            notifyChange()
            return false
        }

        startWatchdog(for: proc)

        statusModel.setRunning(true)
        notifyChange()

        let readHandle = stdoutPipe.fileHandleForReading
        let model = self.statusModel
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            Self.readStdoutLines(from: readHandle, into: model) {
                self?.recordStdoutActivity()
            }
            doneSemaphore.signal()
        }

        return true
    }

    /// Daemon stderr (human-readable log) → ~/Library/Logs/Wingpane/wingpaned.log.
    /// Never leave stderr on an unread Pipe: a full pipe would block the daemon.
    static func daemonLogHandle() -> FileHandle? {
        let dir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Logs/Wingpane", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("wingpaned.log")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? NSNumber, size.intValue > 5_000_000 {
            try? FileManager.default.removeItem(at: url) // simple rotation: keep it under 5 MB
        }
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return nil }
        handle.seekToEndOfFile()
        return handle
    }

    public func stop(gracePeriod: TimeInterval? = nil) {
        let wasInside = (statusModel.ucState == .inside)
        lock.lock()
        guard let proc = process else {
            lock.unlock()
            if wasInside {
                cursorRestore()
            }
            statusModel.setRunning(false)
            statusModel.apply(.stopped)
            notifyChange()
            return
        }
        isStopping = true
        watchdogTimer?.cancel()
        watchdogTimer = nil
        try? stdinHandle?.close()
        stdinHandle = nil
        let doneSemaphore = readerDoneSemaphore
        lock.unlock()

        let pid = proc.processIdentifier
        if proc.isRunning && pid > 0 {
            _ = Darwin.kill(pid, SIGINT)

            let timeout = gracePeriod ?? stopGracePeriod
            let deadline = Date().addingTimeInterval(timeout)
            while proc.isRunning && Date() < deadline {
                Thread.sleep(forTimeInterval: 0.01)
            }

            if proc.isRunning {
                _ = Darwin.kill(pid, SIGKILL)
                proc.waitUntilExit()
            }
        }

        cursorRestore()

        _ = doneSemaphore?.wait(timeout: .now() + 0.2)

        lock.lock()
        if process === proc {
            process = nil
        }
        isStopping = false
        lock.unlock()

        statusModel.setRunning(false)
        statusModel.apply(.stopped)
        notifyChange()
    }

    public func restart() {
        lock.lock()
        _restartCount += 1
        lock.unlock()
        stop()
        _ = start()
    }

    public func toggleStartStop() {
        if isRunning {
            stop()
        } else {
            _ = start()
        }
    }

    @discardableResult
    public func openAppOnPhone() -> Bool {
        guard let adbPath = adbLocator.locate() else {
            statusModel.apply(.adbMissing)
            return false
        }
        let serial = selectedSerial
            ?? statusModel.currentDevice?.serial
            ?? statusModel.devices.first(where: { $0.state == "device" })?.serial

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: adbPath)
        proc.arguments = AdbLocator.openPhoneAppArguments(serial: serial)
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            return true
        } catch {
            return false
        }
    }

    private func recordStdoutActivity() {
        lock.lock()
        lastStdoutAt = Date()
        lock.unlock()
    }

    private func startWatchdog(for proc: Process) {
        let interval = max(min(watchdogTimeout / 6.0, 0.2), 0.02)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInteractive))
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.checkWatchdog(for: proc)
        }
        lock.lock()
        watchdogTimer?.cancel()
        watchdogTimer = timer
        lock.unlock()
        timer.resume()
    }

    private func checkWatchdog(for proc: Process) {
        lock.lock()
        guard process === proc, proc.isRunning else {
            lock.unlock()
            return
        }
        let elapsed = Date().timeIntervalSince(lastStdoutAt)
        let pid = proc.processIdentifier
        lock.unlock()

        guard statusModel.ucState == .inside, elapsed >= watchdogTimeout else {
            return
        }

        if pid > 0 {
            _ = Darwin.kill(pid, SIGKILL)
        }
        cursorRestore()
    }

    private static func readStdoutLines(
        from handle: FileHandle,
        into model: StatusModel,
        onLine: @escaping () -> Void = {}
    ) {
        let fd = handle.fileDescriptor
        var buffer = Data()
        var temp = [UInt8](repeating: 0, count: 4096)
        while true {
            let bytesRead = Darwin.read(fd, &temp, temp.count)
            if bytesRead < 0 {
                if errno == EINTR {
                    continue
                }
                break
            }
            if bytesRead == 0 {
                break
            }
            buffer.append(temp, count: bytesRead)
            while let newlineIndex = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                let lineData = buffer.subdata(in: buffer.startIndex..<newlineIndex)
                buffer.removeSubrange(buffer.startIndex...newlineIndex)
                if let line = String(data: lineData, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                   !line.isEmpty {
                    onLine()
                    model.apply(jsonLine: line)
                }
            }
        }
        if !buffer.isEmpty,
           let line = String(data: buffer, encoding: .utf8)?
               .trimmingCharacters(in: .whitespacesAndNewlines),
           !line.isEmpty {
            onLine()
            model.apply(jsonLine: line)
        }
    }

    private func notifyChange() {
        if Thread.isMainThread {
            objectWillChange.send()
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.objectWillChange.send()
            }
        }
    }
}

public enum PermissionsChecker {
    public static let screenRecordingSettingsURL = URL(
        string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
    )!
    public static let accessibilitySettingsURL = URL(
        string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
    )!
    public static let inputMonitoringSettingsURL = URL(
        string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent"
    )!

    public static func hasScreenRecordingPermission() -> Bool {
        CGPreflightScreenCaptureAccess()
    }

    @discardableResult
    public static func requestScreenRecordingPermission() -> Bool {
        CGRequestScreenCaptureAccess()
    }

    public static func hasAccessibilityPermission(prompt: Bool = false) -> Bool {
        if prompt {
            let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
            return AXIsProcessTrustedWithOptions(options)
        }
        return AXIsProcessTrusted()
    }

    public static func hasInputMonitoringPermission() -> Bool {
        CGPreflightListenEventAccess()
    }
}
