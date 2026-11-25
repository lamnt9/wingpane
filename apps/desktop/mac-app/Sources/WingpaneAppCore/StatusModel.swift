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

    private var _statusText: String = "Stopped"
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
    private var _daemonInputMonitoring: Bool?
    private var _ucState: UcState = .unavailable
    private var _ucEdge: UcEdge = .right
    private var _ucClientReady: Bool = false
    private var _ucReason: String?
    private var _ucEdgeBlocked: Bool = false

    /// Permissions as seen by the daemon process itself (nil until it reports).
    public var daemonInputTrusted: Bool? { lock.lock(); defer { lock.unlock() }; return _daemonInputTrusted }
    public var daemonScreenRecording: Bool? { lock.lock(); defer { lock.unlock() }; return _daemonScreenRecording }
    public var daemonInputMonitoring: Bool? { lock.lock(); defer { lock.unlock() }; return _daemonInputMonitoring }

    public var ucState: UcState { lock.lock(); defer { lock.unlock() }; return _ucState }
    public var ucEdge: UcEdge { lock.lock(); defer { lock.unlock() }; return _ucEdge }
    public var ucClientReady: Bool { lock.lock(); defer { lock.unlock() }; return _ucClientReady }
    public var ucReason: String? { lock.lock(); defer { lock.unlock() }; return _ucReason }
    public var ucEdgeBlocked: Bool { lock.lock(); defer { lock.unlock() }; return _ucEdgeBlocked }

    public var isUcEnabled: Bool {
        let state = ucState
        return state == .armed || state == .inside
    }

    public var ucStatusText: String {
        lock.lock()
        let state = _ucState
        let clientReady = _ucClientReady
        let reason = _ucReason
        let edgeBlocked = _ucEdgeBlocked
        lock.unlock()

        if state == .inside {
            return "Android: inside"
        }
        if reason == "secure_input" {
            return "Android: Secure input on"
        }
        if edgeBlocked {
            return "Android: Edge blocked by another display"
        }
        if !clientReady || reason == "client_not_ready" {
            return "Android: Accessibility service off on tablet"
        }
        return "Android: ready"
    }

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

    public var statusText: String {
        lock.lock()
        defer { lock.unlock() }
        return _statusText
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
        return "None"
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
            _statusText = "Connect your phone (USB debugging on)"

        case .adbMissing:
            _iconState = .error
            _statusText = "adb not found"

        case .waitingDevice(let reason, let devices):
            _devices = devices
            _currentDevice = nil
            _streamWidth = nil
            _streamHeight = nil
            _fps = 0
            _iconState = .waiting
            switch reason {
            case .none:
                _statusText = "Connect your phone (USB debugging on)"
            case .unauthorized:
                _statusText = "Allow USB debugging on the phone"
            case .multiple:
                _statusText = "Several devices — choose one"
            case .unknown:
                _statusText = "Connect your phone (USB debugging on)"
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
            _statusText = "Waiting for the Wingpane app on \(active.displayName)"

        case .listening(let port):
            _listeningPort = port
            _iconState = .waiting
            let label = _currentDevice?.displayName ?? "phone"
            _statusText = "Waiting for the Wingpane app on \(label)"

        case .clientConnected:
            _iconState = .waiting
            let label = _currentDevice?.displayName ?? "phone"
            _statusText = "Waiting for the Wingpane app on \(label)"

        case .streaming(let width, let height, let hz, let hidpi):
            _streamWidth = width
            _streamHeight = height
            _streamHz = hz
            _streamHiDPI = hidpi
            _fps = 0
            _iconState = .streaming
            _statusText = "Streaming \(width)×\(height) · 0 fps"

        case .stats(let fps, _, _, _):
            _fps = fps
            if let width = _streamWidth, let height = _streamHeight {
                _iconState = .streaming
                _statusText = "Streaming \(width)×\(height) · \(fps) fps"
            }

        case .clientDisconnected:
            _streamWidth = nil
            _streamHeight = nil
            _streamHz = nil
            _streamHiDPI = nil
            _fps = 0
            _ucState = .unavailable
            _ucClientReady = false
            _ucReason = nil
            _ucEdgeBlocked = false
            _iconState = .waiting
            let label = _currentDevice?.displayName ?? "phone"
            _statusText = "Waiting for the Wingpane app on \(label)"

        case .error(let message):
            _iconState = .error
            _statusText = message

        case .stopped:
            _streamWidth = nil
            _streamHeight = nil
            _streamHz = nil
            _streamHiDPI = nil
            _fps = 0
            _iconState = .off
            _statusText = "Stopped"
            _daemonInputTrusted = nil
            _daemonScreenRecording = nil
            _daemonInputMonitoring = nil
            _ucState = .unavailable
            _ucClientReady = false
            _ucReason = nil
            _ucEdgeBlocked = false

        case .permissions(let inputTrusted, let screenRecording, let inputMonitoring):
            _daemonInputTrusted = inputTrusted
            _daemonScreenRecording = screenRecording
            if let inputMonitoring {
                _daemonInputMonitoring = inputMonitoring
            }

        case .uc(let state, let edge, let clientReady, let reason, let edgeBlocked):
            _ucState = state
            _ucEdge = edge
            _ucClientReady = clientReady
            _ucReason = reason
            _ucEdgeBlocked = edgeBlocked

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
        let snapshot = "\(_statusText)|\(_iconState)|\(_devices)|\(String(describing: _currentDevice))|\(_isRunning)|\(String(describing: _daemonInputTrusted))|\(String(describing: _daemonScreenRecording))|\(String(describing: _daemonInputMonitoring))|\(_ucState)|\(_ucEdge)|\(_ucClientReady)|\(String(describing: _ucReason))|\(_ucEdgeBlocked)"
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
