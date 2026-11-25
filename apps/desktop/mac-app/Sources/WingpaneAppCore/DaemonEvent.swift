import Foundation

public struct AdbDevice: Codable, Equatable, Sendable, Identifiable {
    public var serial: String
    public var state: String
    public var model: String?

    public var id: String { serial }

    public var displayName: String {
        if let model = model?.trimmingCharacters(in: .whitespacesAndNewlines), !model.isEmpty {
            return model
        }
        return serial
    }

    public init(serial: String, state: String, model: String? = nil) {
        self.serial = serial
        self.state = state
        self.model = model
    }
}

public enum WaitingDeviceReason: Codable, Equatable, Sendable {
    case none
    case multiple
    case unauthorized
    case unknown(String)

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        switch raw {
        case "none":
            self = .none
        case "multiple":
            self = .multiple
        case "unauthorized":
            self = .unauthorized
        default:
            self = .unknown(raw)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .none:
            try container.encode("none")
        case .multiple:
            try container.encode("multiple")
        case .unauthorized:
            try container.encode("unauthorized")
        case .unknown(let raw):
            try container.encode(raw)
        }
    }
}

public enum UcState: String, Codable, Equatable, Sendable {
    case unavailable
    case disabled
    case armed
    case inside
}

public enum DaemonEvent: Codable, Equatable, Sendable {
    case starting(version: String)
    case adbMissing
    case waitingDevice(reason: WaitingDeviceReason, devices: [AdbDevice])
    case device(serial: String, model: String?)
    case listening(port: UInt16)
    case clientConnected(peer: String)
    case streaming(width: Int, height: Int, hz: Int, hidpi: Bool)
    case stats(fps: Int, encodeP95Ms: Double, writeP95Ms: Double, backlogDropped: Int)
    case clientDisconnected(reason: String)
    case error(message: String)
    case permissions(inputTrusted: Bool, screenRecording: Bool, inputMonitoring: Bool? = nil)
    case uc(state: UcState, edge: UcEdge, clientReady: Bool, reason: String?, edgeBlocked: Bool)
    case stopped
    case unknown(String)

    private enum CodingKeys: String, CodingKey {
        case event
        case version
        case reason
        case devices
        case serial
        case model
        case port
        case peer
        case width
        case height
        case hz
        case hidpi
        case fps
        case encodeP95Ms = "encode_p95_ms"
        case writeP95Ms = "write_p95_ms"
        case backlogDropped = "backlog_dropped"
        case message
        case inputTrusted = "input_trusted"
        case screenRecording = "screen_recording"
        case inputMonitoring = "input_monitoring"
        case state
        case edge
        case clientReady = "client_ready"
        case edgeBlocked = "edge_blocked"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let eventName = try container.decode(String.self, forKey: .event)

        switch eventName {
        case "starting":
            let version = try container.decode(String.self, forKey: .version)
            self = .starting(version: version)

        case "adb_missing":
            self = .adbMissing

        case "waiting_device":
            let reason = try container.decode(WaitingDeviceReason.self, forKey: .reason)
            let devices = try container.decodeIfPresent([AdbDevice].self, forKey: .devices) ?? []
            self = .waitingDevice(reason: reason, devices: devices)

        case "device":
            let serial = try container.decode(String.self, forKey: .serial)
            let model = try container.decodeIfPresent(String.self, forKey: .model)
            self = .device(serial: serial, model: model)

        case "listening":
            let port = try container.decode(UInt16.self, forKey: .port)
            self = .listening(port: port)

        case "client_connected":
            let peer = try container.decode(String.self, forKey: .peer)
            self = .clientConnected(peer: peer)

        case "streaming":
            let width = try container.decode(Int.self, forKey: .width)
            let height = try container.decode(Int.self, forKey: .height)
            let hz = try container.decode(Int.self, forKey: .hz)
            let hidpi = try container.decode(Bool.self, forKey: .hidpi)
            self = .streaming(width: width, height: height, hz: hz, hidpi: hidpi)

        case "stats":
            let fps: Int
            if let intFps = try? container.decode(Int.self, forKey: .fps) {
                fps = intFps
            } else {
                let doubleFps = try container.decode(Double.self, forKey: .fps)
                fps = Int(doubleFps.rounded())
            }
            let encodeP95Ms = try container.decode(Double.self, forKey: .encodeP95Ms)
            let writeP95Ms = try container.decode(Double.self, forKey: .writeP95Ms)
            let backlogDropped: Int
            if let intDropped = try? container.decode(Int.self, forKey: .backlogDropped) {
                backlogDropped = intDropped
            } else {
                let doubleDropped = try container.decode(Double.self, forKey: .backlogDropped)
                backlogDropped = Int(doubleDropped.rounded())
            }
            self = .stats(
                fps: fps,
                encodeP95Ms: encodeP95Ms,
                writeP95Ms: writeP95Ms,
                backlogDropped: backlogDropped
            )

        case "client_disconnected":
            let reason = try container.decode(String.self, forKey: .reason)
            self = .clientDisconnected(reason: reason)

        case "error":
            let message = try container.decode(String.self, forKey: .message)
            self = .error(message: message)

        case "permissions":
            self = .permissions(
                inputTrusted: try container.decode(Bool.self, forKey: .inputTrusted),
                screenRecording: try container.decode(Bool.self, forKey: .screenRecording),
                inputMonitoring: try container.decodeIfPresent(Bool.self, forKey: .inputMonitoring)
            )

        case "uc":
            let state = try container.decode(UcState.self, forKey: .state)
            let edge = try container.decode(UcEdge.self, forKey: .edge)
            let clientReady = try container.decode(Bool.self, forKey: .clientReady)
            let reason = try container.decodeIfPresent(String.self, forKey: .reason)
            let edgeBlocked = try container.decodeIfPresent(Bool.self, forKey: .edgeBlocked) ?? false
            self = .uc(
                state: state,
                edge: edge,
                clientReady: clientReady,
                reason: reason,
                edgeBlocked: edgeBlocked
            )

        case "stopped":
            self = .stopped

        default:
            self = .unknown(eventName)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .starting(let version):
            try container.encode("starting", forKey: .event)
            try container.encode(version, forKey: .version)
        case .adbMissing:
            try container.encode("adb_missing", forKey: .event)
        case .waitingDevice(let reason, let devices):
            try container.encode("waiting_device", forKey: .event)
            try container.encode(reason, forKey: .reason)
            try container.encode(devices, forKey: .devices)
        case .device(let serial, let model):
            try container.encode("device", forKey: .event)
            try container.encode(serial, forKey: .serial)
            try container.encodeIfPresent(model, forKey: .model)
        case .listening(let port):
            try container.encode("listening", forKey: .event)
            try container.encode(port, forKey: .port)
        case .clientConnected(let peer):
            try container.encode("client_connected", forKey: .event)
            try container.encode(peer, forKey: .peer)
        case .streaming(let width, let height, let hz, let hidpi):
            try container.encode("streaming", forKey: .event)
            try container.encode(width, forKey: .width)
            try container.encode(height, forKey: .height)
            try container.encode(hz, forKey: .hz)
            try container.encode(hidpi, forKey: .hidpi)
        case .stats(let fps, let encodeP95Ms, let writeP95Ms, let backlogDropped):
            try container.encode("stats", forKey: .event)
            try container.encode(fps, forKey: .fps)
            try container.encode(encodeP95Ms, forKey: .encodeP95Ms)
            try container.encode(writeP95Ms, forKey: .writeP95Ms)
            try container.encode(backlogDropped, forKey: .backlogDropped)
        case .clientDisconnected(let reason):
            try container.encode("client_disconnected", forKey: .event)
            try container.encode(reason, forKey: .reason)
        case .error(let message):
            try container.encode("error", forKey: .event)
            try container.encode(message, forKey: .message)
        case .permissions(let inputTrusted, let screenRecording, let inputMonitoring):
            try container.encode("permissions", forKey: .event)
            try container.encode(inputTrusted, forKey: .inputTrusted)
            try container.encode(screenRecording, forKey: .screenRecording)
            try container.encodeIfPresent(inputMonitoring, forKey: .inputMonitoring)
        case .uc(let state, let edge, let clientReady, let reason, let edgeBlocked):
            try container.encode("uc", forKey: .event)
            try container.encode(state, forKey: .state)
            try container.encode(edge, forKey: .edge)
            try container.encode(clientReady, forKey: .clientReady)
            try container.encodeIfPresent(reason, forKey: .reason)
            try container.encode(edgeBlocked, forKey: .edgeBlocked)
        case .stopped:
            try container.encode("stopped", forKey: .event)
        case .unknown(let name):
            try container.encode(name, forKey: .event)
        }
    }

    public static func decode(jsonLine: String) throws -> DaemonEvent {
        let trimmed = jsonLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8) else {
            throw DecodingError.dataCorrupted(
                DecodingError.Context(codingPath: [], debugDescription: "Invalid UTF-8 line")
            )
        }
        return try JSONDecoder().decode(DaemonEvent.self, from: data)
    }
}
