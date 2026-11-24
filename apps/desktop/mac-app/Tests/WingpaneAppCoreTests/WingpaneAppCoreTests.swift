import Darwin
import Foundation
import XCTest
@testable import WingpaneAppCore

final class WingpaneAppCoreTests: XCTestCase {

    // MARK: - §2 Event Decoding & Unknown Event/Field Tolerance

    func testDecodeAllSection2Events() throws {
        let starting = try DaemonEvent.decode(
            jsonLine: #"{"event":"starting","version":"0.1.0"}"#
        )
        XCTAssertEqual(starting, .starting(version: "0.1.0"))

        let adbMissing = try DaemonEvent.decode(
            jsonLine: #"{"event":"adb_missing"}"#
        )
        XCTAssertEqual(adbMissing, .adbMissing)

        let waitingNone = try DaemonEvent.decode(
            jsonLine: #"{"event":"waiting_device","reason":"none","devices":[]}"#
        )
        XCTAssertEqual(waitingNone, .waitingDevice(reason: .none, devices: []))

        let waitingMultiple = try DaemonEvent.decode(
            jsonLine: #"{"event":"waiting_device","reason":"multiple","devices":[{"serial":"S1","state":"device","model":"Pixel 8"},{"serial":"S2","state":"device"}]}"#
        )
        XCTAssertEqual(
            waitingMultiple,
            .waitingDevice(
                reason: .multiple,
                devices: [
                    AdbDevice(serial: "S1", state: "device", model: "Pixel 8"),
                    AdbDevice(serial: "S2", state: "device", model: nil),
                ]
            )
        )

        let waitingUnauthorized = try DaemonEvent.decode(
            jsonLine: #"{"event":"waiting_device","reason":"unauthorized","devices":[{"serial":"S1","state":"unauthorized"}]}"#
        )
        XCTAssertEqual(
            waitingUnauthorized,
            .waitingDevice(
                reason: .unauthorized,
                devices: [AdbDevice(serial: "S1", state: "unauthorized", model: nil)]
            )
        )

        let device = try DaemonEvent.decode(
            jsonLine: #"{"event":"device","serial":"R58M123","model":"Galaxy Tab S9"}"#
        )
        XCTAssertEqual(device, .device(serial: "R58M123", model: "Galaxy Tab S9"))

        let listening = try DaemonEvent.decode(
            jsonLine: #"{"event":"listening","port":7788}"#
        )
        XCTAssertEqual(listening, .listening(port: 7788))

        let clientConnected = try DaemonEvent.decode(
            jsonLine: #"{"event":"client_connected","peer":"127.0.0.1:52100"}"#
        )
        XCTAssertEqual(clientConnected, .clientConnected(peer: "127.0.0.1:52100"))

        let streaming = try DaemonEvent.decode(
            jsonLine: #"{"event":"streaming","width":2340,"height":1080,"hz":60,"hidpi":true}"#
        )
        XCTAssertEqual(streaming, .streaming(width: 2340, height: 1080, hz: 60, hidpi: true))

        let stats = try DaemonEvent.decode(
            jsonLine: #"{"event":"stats","fps":57,"encode_p95_ms":4.5,"write_p95_ms":1.2,"backlog_dropped":0}"#
        )
        XCTAssertEqual(
            stats,
            .stats(fps: 57, encodeP95Ms: 4.5, writeP95Ms: 1.2, backlogDropped: 0)
        )

        let clientDisconnected = try DaemonEvent.decode(
            jsonLine: #"{"event":"client_disconnected","reason":"peer closed connection"}"#
        )
        XCTAssertEqual(clientDisconnected, .clientDisconnected(reason: "peer closed connection"))

        let errorEvent = try DaemonEvent.decode(
            jsonLine: #"{"event":"error","message":"Screen Recording permission lost"}"#
        )
        XCTAssertEqual(errorEvent, .error(message: "Screen Recording permission lost"))

        let stopped = try DaemonEvent.decode(
            jsonLine: #"{"event":"stopped"}"#
        )
        XCTAssertEqual(stopped, .stopped)
    }

    func testDecodeUnknownFieldsAndUnknownEvents() throws {
        // Known event with extra unknown fields
        let streamingWithExtra = try DaemonEvent.decode(
            jsonLine: #"{"event":"streaming","width":2340,"height":1080,"hz":60,"hidpi":true,"codec":"h264","extra_nested":{"a":1}}"#
        )
        XCTAssertEqual(
            streamingWithExtra,
            .streaming(width: 2340, height: 1080, hz: 60, hidpi: true)
        )

        // Unknown event type should not throw
        let futureEvent = try DaemonEvent.decode(
            jsonLine: #"{"event":"telemetry_ping","timestamp":123456789}"#
        )
        XCTAssertEqual(futureEvent, .unknown("telemetry_ping"))

        // Unknown waiting_device reason should not throw
        let unknownReason = try DaemonEvent.decode(
            jsonLine: #"{"event":"waiting_device","reason":"offline","devices":[]}"#
        )
        XCTAssertEqual(
            unknownReason,
            .waitingDevice(reason: .unknown("offline"), devices: [])
        )
    }

    // MARK: - MA-7 Status Text & Icon State Mapping

    func testStatusTextAndIconStateForEachMA7State() {
        let model = StatusModel()

        // Initial state: Stopped
        XCTAssertEqual(model.statusText, "Stopped")
        XCTAssertEqual(model.iconState, .off)
        XCTAssertEqual(model.menuHeaderTitle, "Wingpane — Stopped")

        // 1. adb not found
        model.apply(.adbMissing)
        XCTAssertEqual(model.statusText, "adb not found")
        XCTAssertEqual(model.iconState, .error)

        // 2. Connect your phone (USB debugging on)
        model.apply(.waitingDevice(reason: .none, devices: []))
        XCTAssertEqual(model.statusText, "Connect your phone (USB debugging on)")
        XCTAssertEqual(model.iconState, .waiting)

        // 3. Allow USB debugging on the phone
        model.apply(
            .waitingDevice(
                reason: .unauthorized,
                devices: [AdbDevice(serial: "SER1", state: "unauthorized")]
            )
        )
        XCTAssertEqual(model.statusText, "Allow USB debugging on the phone")
        XCTAssertEqual(model.iconState, .waiting)

        // 4. Several devices — choose one
        model.apply(
            .waitingDevice(
                reason: .multiple,
                devices: [
                    AdbDevice(serial: "SER1", state: "device", model: "Pixel 8"),
                    AdbDevice(serial: "SER2", state: "device", model: "Galaxy S24"),
                ]
            )
        )
        XCTAssertEqual(model.statusText, "Several devices — choose one")
        XCTAssertEqual(model.iconState, .waiting)
        XCTAssertEqual(model.devices.count, 2)

        // 5. Waiting for the Wingpane app on <model>
        model.apply(.device(serial: "SER1", model: "Pixel 8"))
        XCTAssertEqual(model.statusText, "Waiting for the Wingpane app on Pixel 8")
        XCTAssertEqual(model.iconState, .waiting)

        // Fallback to serial when model is absent
        model.apply(.device(serial: "SER2", model: nil))
        XCTAssertEqual(model.statusText, "Waiting for the Wingpane app on SER2")
        XCTAssertEqual(model.iconState, .waiting)

        model.apply(.device(serial: "SER1", model: "Pixel 8"))
        model.apply(.listening(port: 7788))
        XCTAssertEqual(model.statusText, "Waiting for the Wingpane app on Pixel 8")
        XCTAssertEqual(model.iconState, .waiting)

        // 6. Streaming W×H · N fps
        model.apply(.streaming(width: 2340, height: 1080, hz: 60, hidpi: true))
        XCTAssertEqual(model.statusText, "Streaming 2340×1080 · 0 fps")
        XCTAssertEqual(model.iconState, .streaming)

        model.apply(.stats(fps: 57, encodeP95Ms: 4.1, writeP95Ms: 1.0, backlogDropped: 0))
        XCTAssertEqual(model.statusText, "Streaming 2340×1080 · 57 fps")
        XCTAssertEqual(model.iconState, .streaming)

        // Unknown event does not corrupt current status
        model.apply(.unknown("future_event"))
        XCTAssertEqual(model.statusText, "Streaming 2340×1080 · 57 fps")
        XCTAssertEqual(model.iconState, .streaming)

        // Client disconnected returns to waiting for app on <model>
        model.apply(.clientDisconnected(reason: "eof"))
        XCTAssertEqual(model.statusText, "Waiting for the Wingpane app on Pixel 8")
        XCTAssertEqual(model.iconState, .waiting)

        // Error state
        model.apply(.error(message: "Capture permission denied"))
        XCTAssertEqual(model.statusText, "Capture permission denied")
        XCTAssertEqual(model.iconState, .error)

        // 7. Stopped
        model.apply(.stopped)
        XCTAssertEqual(model.statusText, "Stopped")
        XCTAssertEqual(model.iconState, .off)
    }

    // MARK: - MA-3 AdbLocator Order

    func testAdbLocatorStrictOrder() {
        let env = ["ANDROID_HOME": "/custom/android-sdk"]
        let home = "/Users/tester"
        let expectedOrder = [
            "/custom/android-sdk/platform-tools/adb",
            "/Users/tester/Library/Android/sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
        ]

        let locatorAll = AdbLocator(
            environment: env,
            homeDirectory: home,
            fileExists: { _ in true }
        )
        XCTAssertEqual(locatorAll.candidatePaths(), expectedOrder)
        XCTAssertEqual(locatorAll.locate(), expectedOrder[0])

        // When ANDROID_HOME candidate doesn't exist, picks ~/Library/Android/sdk/platform-tools/adb
        let locatorSecond = AdbLocator(
            environment: env,
            homeDirectory: home,
            fileExists: { path in path == expectedOrder[1] || path == expectedOrder[2] }
        )
        XCTAssertEqual(locatorSecond.locate(), expectedOrder[1])

        // When only /opt/homebrew/bin/adb and /usr/local/bin/adb exist, picks /opt/homebrew/bin/adb
        let locatorThird = AdbLocator(
            environment: env,
            homeDirectory: home,
            fileExists: { path in path == expectedOrder[2] || path == expectedOrder[3] }
        )
        XCTAssertEqual(locatorThird.locate(), expectedOrder[2])

        // When only /usr/local/bin/adb exists, picks /usr/local/bin/adb
        let locatorFourth = AdbLocator(
            environment: env,
            homeDirectory: home,
            fileExists: { path in path == expectedOrder[3] }
        )
        XCTAssertEqual(locatorFourth.locate(), expectedOrder[3])

        // When none exist, returns nil
        let locatorNone = AdbLocator(
            environment: env,
            homeDirectory: home,
            fileExists: { _ in false }
        )
        XCTAssertNil(locatorNone.locate())

        // Verify openPhoneAppArguments helper
        XCTAssertEqual(
            AdbLocator.openPhoneAppArguments(serial: "ABC123"),
            ["-s", "ABC123", "shell", "am", "start", "-n", "io.wingpane/.MainActivity"]
        )
    }

    // MARK: - Options Persistence & CLI Arguments

    func testOptionsPersistenceAndArguments() {
        let suiteName = "io.wingpane.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let defaultOpts = Options.load(from: defaults)
        XCTAssertEqual(defaultOpts, Options(hidpi: true, testPattern: false, bitrateMbps: 20))
        XCTAssertEqual(
            defaultOpts.daemonArguments(serial: nil),
            ["--status-json", "--hidpi", "true", "--bitrate-mbps", "20"]
        )

        let customOpts = Options(hidpi: false, testPattern: true, bitrateMbps: 30)
        customOpts.save(to: defaults)

        let reloaded = Options.load(from: defaults)
        XCTAssertEqual(reloaded, customOpts)
        XCTAssertEqual(
            reloaded.daemonArguments(serial: "SER99"),
            ["--status-json", "--hidpi", "false", "--bitrate-mbps", "30", "--test-pattern", "--serial", "SER99"]
        )
    }

    // MARK: - DaemonController with Fake Daemon Script (Deliverable 3)

    func testDaemonControllerStreamsEventsAndStopsWithSIGINT() throws {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let fixtureScript = packageRoot.appendingPathComponent("Tests/Fixtures/fake-wingpaned.sh")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixtureScript.path))

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("wingpane-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let signalFile = tempDir.appendingPathComponent("signal.txt")
        let argsFile = tempDir.appendingPathComponent("args.txt")
        let adbFile = tempDir.appendingPathComponent("adb.txt")

        let suiteName = "io.wingpane.tests.controller.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let fakeAdbPath = "/opt/homebrew/bin/adb"
        let adbLocator = AdbLocator(
            environment: [:],
            homeDirectory: "/Users/test",
            fileExists: { $0 == fakeAdbPath }
        )

        let model = StatusModel()
        let controller = DaemonController(
            statusModel: model,
            adbLocator: adbLocator,
            userDefaults: defaults,
            daemonExecutableURL: fixtureScript,
            environment: [
                "FAKE_DAEMON_SIGNAL_FILE": signalFile.path,
                "FAKE_DAEMON_ARGS_FILE": argsFile.path,
                "FAKE_DAEMON_ADB_FILE": adbFile.path,
            ],
            stopGracePeriod: 2.0
        )

        XCTAssertTrue(controller.start())

        // Wait up to 2 seconds for model to reach "Streaming 2340×1080 · 57 fps"
        let deadline = Date().addingTimeInterval(2.0)
        while model.statusText != "Streaming 2340×1080 · 57 fps" && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.02)
        }

        XCTAssertEqual(model.statusText, "Streaming 2340×1080 · 57 fps")
        XCTAssertEqual(model.iconState, .streaming)
        XCTAssertEqual(model.currentDevice?.displayName, "Pixel 8")

        // Verify WINGPANE_ADB and CLI flags were passed to daemon
        let recordedAdb = try String(contentsOf: adbFile, encoding: .utf8)
        XCTAssertEqual(recordedAdb, fakeAdbPath)
        let recordedArgs = try String(contentsOf: argsFile, encoding: .utf8)
        XCTAssertEqual(recordedArgs, "--status-json --hidpi true --bitrate-mbps 20")

        // Changing options while running restarts the daemon with new flags (MA-8)
        controller.setOptions(Options(hidpi: false, testPattern: true, bitrateMbps: 30))
        XCTAssertEqual(controller.restartCount, 1)

        let restartDeadline = Date().addingTimeInterval(2.0)
        while (try? String(contentsOf: argsFile, encoding: .utf8)) != "--status-json --hidpi false --bitrate-mbps 30 --test-pattern"
            && Date() < restartDeadline {
            Thread.sleep(forTimeInterval: 0.02)
        }
        XCTAssertEqual(
            try String(contentsOf: argsFile, encoding: .utf8),
            "--status-json --hidpi false --bitrate-mbps 30 --test-pattern"
        )

        // Now stop and verify SIGINT was sent and status transitions to Stopped
        try? FileManager.default.removeItem(at: signalFile)
        controller.stop()

        XCTAssertFalse(controller.isRunning)
        XCTAssertEqual(model.statusText, "Stopped")
        XCTAssertEqual(model.iconState, .off)

        let recordedSignal = try String(contentsOf: signalFile, encoding: .utf8)
        XCTAssertEqual(recordedSignal, "SIGINT")
    }
}
