import AppKit
import Combine
import Foundation

public enum IconState: String, Equatable, Sendable {
    case off
    case streaming
    case waiting
    case error
}

public final class StatusModel: ObservableObject, @unchecked Sendable {
    private let lock = NSLock()

    private var _statusText = LocalizedText("Stopped", "Đã dừng")
    private var _iconState: IconState = .off
    private var _devices: [AdbDevice] = []
    private var _currentDevice: AdbDevice?
    private var _listeningPort: UInt16?
    private var _streamWidth: Int?
    private var _streamHeight: Int?
    private var _streamHz: Int?
    private var _streamHiDPI: Bool?
    private var _fps: Int = 0
    private var _isRunning: Bool = false
    private var _lastEvent: DaemonEvent?
    private var _daemonInputTrusted: Bool?
    private var _daemonScreenRecording: Bool?

    /// Permissions as seen by the daemon process itself (nil until it reports).
    public var daemonInputTrusted: Bool? { lock.lock(); defer { lock.unlock() }; return _daemonInputTrusted }
    public var daemonScreenRecording: Bool? { lock.lock(); defer { lock.unlock() }; return _daemonScreenRecording }

    // While a menu is open, republishing makes SwiftUI rebuild it and closes open submenus.
    // Dedupe identical states and hold changes until tracking ends.
    private var menuOpen = false
    private var pendingChange = false
    private var lastSnapshot = ""
    private var observers: [NSObjectProtocol] = []

    public init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: NSMenu.didBeginTrackingNotification, object: nil, queue: .main) { [weak self] _ in
            self?.menuOpen = true
        })
        observers.append(center.addObserver(forName: NSMenu.didEndTrackingNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.menuOpen = false
            if self.pendingChange { self.pendingChange = false; self.objectWillChange.send() }
        })
    }

    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    private static func waitingText(_ device: String?) -> LocalizedText {
        LocalizedText("Waiting for the Wingpane app on \(device ?? "phone")", "Đang chờ app Wingpane trên \(device ?? "tablet")")
    }

    private static func streamingText(_ width: Int, _ height: Int, _ fps: Int) -> LocalizedText {
        LocalizedText("Streaming \(width)×\(height) · \(fps) fps", "Đang truyền \(width)×\(height) · \(fps) fps")
    }

    public var statusText: String {
        lock.lock()
        defer { lock.unlock() }
        return _statusText.text()
    }

    public var iconState: IconState {
        lock.lock()
        defer { lock.unlock() }
        return _iconState
    }

    public var devices: [AdbDevice] {
        lock.lock()
        defer { lock.unlock() }
        return _devices
    }

    public var currentDevice: AdbDevice? {
        lock.lock()
        defer { lock.unlock() }
        return _currentDevice
    }

    public var listeningPort: UInt16? {
        lock.lock()
        defer { lock.unlock() }
        return _listeningPort
    }

    public var streamWidth: Int? {
        lock.lock()
        defer { lock.unlock() }
        return _streamWidth
    }

    public var streamHeight: Int? {
        lock.lock()
        defer { lock.unlock() }
        return _streamHeight
    }

    public var fps: Int {
        lock.lock()
        defer { lock.unlock() }
        return _fps
    }

    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isRunning
    }

    public var lastEvent: DaemonEvent? {
        lock.lock()
        defer { lock.unlock() }
        return _lastEvent
    }

    public var menuHeaderTitle: String {
        "Wingpane — \(statusText)"
    }

    public var deviceDisplayTitle: String {
        if let device = currentDevice {
            return device.displayName
        }
        let allDevices = devices
        if allDevices.count == 1 {
            return allDevices[0].displayName
        }
        return L("None", "Không có")
    }

    public func setRunning(_ running: Bool) {
        lock.lock()
        _isRunning = running
        lock.unlock()
        notifyChange()
    }

    public func apply(_ event: DaemonEvent) {
        lock.lock()
        _lastEvent = event

        switch event {
        case .starting:
            _iconState = .waiting
            _statusText = LocalizedText("Connect your phone (USB debugging on)", "Cắm tablet (bật USB debugging)")

        case .adbMissing:
            _iconState = .error
            _statusText = LocalizedText("adb not found", "Không tìm thấy adb")

        case .waitingDevice(let reason, let devices):
            _devices = devices
            _currentDevice = nil
            _streamWidth = nil
            _streamHeight = nil
            _fps = 0
            _iconState = .waiting
            switch reason {
            case .none:
                _statusText = LocalizedText("Connect your phone (USB debugging on)", "Cắm tablet (bật USB debugging)")
            case .unauthorized:
                _statusText = LocalizedText("Allow USB debugging on the phone", "Cho phép USB debugging trên tablet")
            case .multiple:
                _statusText = LocalizedText("Several devices — choose one", "Nhiều thiết bị — chọn một")
            case .unknown:
                _statusText = LocalizedText("Connect your phone (USB debugging on)", "Cắm tablet (bật USB debugging)")
            }

        case .device(let serial, let model):
            let active = AdbDevice(serial: serial, state: "device", model: model)
            _currentDevice = active
            if let idx = _devices.firstIndex(where: { $0.serial == serial }) {
                _devices[idx] = active
            } else {
                _devices.append(active)
            }
            _streamWidth = nil
            _streamHeight = nil
            _fps = 0
            _iconState = .waiting
            _statusText = Self.waitingText(active.displayName)

        case .listening(let port):
            _listeningPort = port
            _iconState = .waiting
            _statusText = Self.waitingText(_currentDevice?.displayName)

        case .clientConnected:
            _iconState = .waiting
            _statusText = Self.waitingText(_currentDevice?.displayName)

        case .streaming(let width, let height, let hz, let hidpi):
            _streamWidth = width
            _streamHeight = height
            _streamHz = hz
            _streamHiDPI = hidpi
            _fps = 0
            _iconState = .streaming
            _statusText = Self.streamingText(width, height, 0)

        case .stats(let fps, _, _, _):
            _fps = fps
            if let width = _streamWidth, let height = _streamHeight {
                _iconState = .streaming
                _statusText = Self.streamingText(width, height, fps)
            }

        case .clientDisconnected:
            _streamWidth = nil
            _streamHeight = nil
            _streamHz = nil
            _streamHiDPI = nil
            _fps = 0
            _iconState = .waiting
            _statusText = Self.waitingText(_currentDevice?.displayName)

        case .error(let message):
            _iconState = .error
            _statusText = LocalizedText(verbatim: message)

        case .stopped:
            _streamWidth = nil
            _streamHeight = nil
            _streamHz = nil
            _streamHiDPI = nil
            _fps = 0
            _iconState = .off
            _statusText = LocalizedText("Stopped", "Đã dừng")
            _daemonInputTrusted = nil
            _daemonScreenRecording = nil

        case .permissions(let inputTrusted, let screenRecording):
            _daemonInputTrusted = inputTrusted
            _daemonScreenRecording = screenRecording

        case .unknown:
            break
        }
        lock.unlock()
        notifyChange()
    }

    @discardableResult
    public func apply(jsonLine: String) -> DaemonEvent? {
        guard let event = try? DaemonEvent.decode(jsonLine: jsonLine) else {
            return nil
        }
        apply(event)
        return event
    }

    private func notifyChange() {
        lock.lock()
        let snapshot = "\(_statusText)|\(_iconState)|\(_devices)|\(String(describing: _currentDevice))|\(_isRunning)|\(String(describing: _daemonInputTrusted))|\(String(describing: _daemonScreenRecording))"
        let changed = snapshot != lastSnapshot
        lastSnapshot = snapshot
        lock.unlock()
        guard changed else { return }
        let send = { [weak self] in
            guard let self else { return }
            if self.menuOpen { self.pendingChange = true } else { self.objectWillChange.send() }
        }
        if Thread.isMainThread { send() } else { DispatchQueue.main.async(execute: send) }
    }
}
