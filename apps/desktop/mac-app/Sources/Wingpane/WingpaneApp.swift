import AppKit
import ServiceManagement
import SwiftUI
import WingpaneAppCore

final class AppDelegate: NSObject, NSApplicationDelegate {
    var controller: DaemonController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        _ = PermissionsChecker.hasScreenRecordingPermission()
        _ = PermissionsChecker.hasAccessibilityPermission(prompt: false)
        // Start immediately: the daemon just waits until a phone is connected (decided 2026-11-25).
        _ = controller?.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        controller?.stop()
    }
}

@main
struct WingpaneApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var statusModel: StatusModel
    @StateObject private var controller: DaemonController
    @State private var screenRecordingGranted: Bool = PermissionsChecker.hasScreenRecordingPermission()
    @State private var accessibilityGranted: Bool = PermissionsChecker.hasAccessibilityPermission(prompt: false)
    @State private var launchAtLoginEnabled: Bool = (SMAppService.mainApp.status == .enabled)

    init() {
        let model = StatusModel()
        let daemonController = DaemonController(statusModel: model)
        _statusModel = StateObject(wrappedValue: model)
        _controller = StateObject(wrappedValue: daemonController)
        appDelegate.controller = daemonController
    }

    var body: some Scene {
        MenuBarExtra {
            menuContent
        } label: {
            Image(nsImage: Self.menuBarIcon(for: statusModel.iconState))
        }
        .menuBarExtraStyle(.menu)
    }

    @ViewBuilder
    private var menuContent: some View {
        Text(statusModel.menuHeaderTitle)
            .help(statusModel.statusText == "adb not found" ? AdbLocator.installHint : "")

        Menu("Device: \(statusModel.deviceDisplayTitle)") {
            if statusModel.devices.isEmpty {
                Text("No devices")
                    .disabled(true)
            } else {
                ForEach(statusModel.devices) { device in
                    let isSelected = isDeviceSelected(device)
                    Button(isSelected ? "\(device.displayName) ✓" : device.displayName) {
                        controller.selectDevice(serial: device.serial)
                    }
                }
            }
        }

        Divider()

        Button(statusModel.isRunning ? "Stop" : "Start") {
            controller.toggleStartStop()
        }
        .keyboardShortcut("s", modifiers: .command)

        Button("Open app on phone") {
            _ = controller.openAppOnPhone()
        }

        Divider()

        Menu("Options") {
            Button(controller.options.hidpi ? "HiDPI ✓" : "HiDPI") {
                var updated = controller.options
                updated.hidpi.toggle()
                controller.setOptions(updated)
            }

            Button(controller.options.testPattern ? "Test pattern ✓" : "Test pattern") {
                var updated = controller.options
                updated.testPattern.toggle()
                controller.setOptions(updated)
            }

            Menu("Bitrate") {
                ForEach(Options.supportedBitrates, id: \.self) { mbps in
                    let isCurrent = (controller.options.bitrateMbps == mbps)
                    Button(isCurrent ? "\(mbps) Mbps ✓" : "\(mbps) Mbps") {
                        var updated = controller.options
                        updated.bitrateMbps = mbps
                        controller.setOptions(updated)
                    }
                }
            }
        }

        Menu("Permissions") {
            // Daemon-reported state wins: macOS grants are per process and the daemon does the injecting.
            let screenOK = statusModel.daemonScreenRecording ?? screenRecordingGranted
            let accessOK = statusModel.daemonInputTrusted ?? accessibilityGranted
            Button("Screen Recording \(screenOK ? "✓" : "✗")") {
                openSettingsOnce(PermissionsChecker.screenRecordingSettingsURL)
            }

            Button("Accessibility \(accessOK ? "✓" : "✗")") {
                openSettingsOnce(PermissionsChecker.accessibilitySettingsURL)
            }
        }

        Button(launchAtLoginEnabled ? "Launch at login ✓" : "Launch at login") {
            toggleLaunchAtLogin()
        }

        Divider()

        Button("About Wingpane") {
            let info = Bundle.main.infoDictionary
            NSApp.activate(ignoringOtherApps: true)
            NSApp.orderFrontStandardAboutPanel(options: [
                .applicationName: "Wingpane",
                .applicationVersion: info?["CFBundleShortVersionString"] as? String ?? "",
                .version: info?["CFBundleVersion"] as? String ?? "",
                .credits: NSAttributedString(string: "Turn your Android tablet into a Mac secondary display.\n\nhttps://github.com/lamnt9/wingpane"),
                NSApplication.AboutPanelOptionKey(rawValue: "Copyright"): info?["NSHumanReadableCopyright"] as? String ?? "Copyright © 2026 Wingpane. All rights reserved.",
            ])
        }

        Button("Quit Wingpane") {
            controller.stop()
            NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q", modifiers: .command)
    }

    private func isDeviceSelected(_ device: AdbDevice) -> Bool {
        if let selected = controller.selectedSerial {
            return selected == device.serial
        }
        return statusModel.currentDevice?.serial == device.serial
    }

    @MainActor private static var lastSettingsOpen = Date.distantPast
    private func openSettingsOnce(_ url: URL) {
        // No system prompt (it re-opened Settings repeatedly); one window per click, debounced.
        DispatchQueue.main.async {
            guard Date().timeIntervalSince(Self.lastSettingsOpen) > 3 else { return }
            Self.lastSettingsOpen = Date()
            // The daemon (not this app) injects input, so it must be listed itself. No system prompt
            // (it kept reappearing): open Settings and reveal the file to drag into the list / add with "+".
            if let daemon = DaemonController.locateDaemonExecutable() {
                NSWorkspace.shared.activateFileViewerSelecting([daemon])
            }
            NSWorkspace.shared.open(url)
        }
    }

    private func refreshPermissions() {
        screenRecordingGranted = PermissionsChecker.hasScreenRecordingPermission()
        accessibilityGranted = PermissionsChecker.hasAccessibilityPermission(prompt: false)
    }

    private func toggleLaunchAtLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            // Ignore registration errors in unbundled/unsigned dev runs.
        }
        launchAtLoginEnabled = (SMAppService.mainApp.status == .enabled)
    }

    private static func menuBarIcon(for state: IconState) -> NSImage {
        let symbolName: String
        let color: NSColor

        switch state {
        case .off:
            symbolName = "rectangle.on.rectangle"
            color = .systemGray
        case .streaming:
            symbolName = "rectangle.on.rectangle.fill"
            color = .systemGreen
        case .waiting:
            symbolName = "rectangle.on.rectangle"
            color = .systemYellow
        case .error:
            symbolName = "rectangle.on.rectangle"
            color = .systemRed
        }

        let baseImage = NSImage(
            systemSymbolName: symbolName,
            accessibilityDescription: "Wingpane (\(state.rawValue))"
        ) ?? NSImage(size: NSSize(width: 16, height: 16))

        let config = NSImage.SymbolConfiguration(paletteColors: [color])
        let configured = baseImage.withSymbolConfiguration(config) ?? baseImage
        configured.isTemplate = false
        return configured
    }
}
