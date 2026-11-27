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
    // Re-renders the menu when the language changes; L(...) reads the same UserDefaults key.
    @AppStorage(AppLanguage.defaultsKey) private var languageRaw: String = AppLanguage.en.rawValue

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
        let _ = languageRaw
        Text(statusModel.menuHeaderTitle)
            .help(statusModel.lastEvent == .adbMissing ? AdbLocator.installHint : "")

        Menu(L("Device: ", "Thiết bị: ") + statusModel.deviceDisplayTitle) {
            if statusModel.devices.isEmpty {
                Text(L("No devices", "Không có thiết bị"))
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

        Button(statusModel.isRunning ? L("Stop", "Dừng") : L("Start", "Bắt đầu")) {
            controller.toggleStartStop()
        }
        .keyboardShortcut("s", modifiers: .command)

        Button(L("Open app on phone", "Mở app trên tablet")) {
            _ = controller.openAppOnPhone()
        }

        Divider()

        Menu(L("Options", "Tuỳ chọn")) {
            Button(controller.options.hidpi ? "HiDPI ✓" : "HiDPI") {
                var updated = controller.options
                updated.hidpi.toggle()
                controller.setOptions(updated)
            }

            Button(controller.options.testPattern ? L("Test pattern ✓", "Hình mẫu thử ✓") : L("Test pattern", "Hình mẫu thử")) {
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

        Menu(L("Permissions", "Quyền")) {
            // Daemon-reported state wins: macOS grants are per process and the daemon does the injecting.
            let screenOK = statusModel.daemonScreenRecording ?? screenRecordingGranted
            let accessOK = statusModel.daemonInputTrusted ?? accessibilityGranted
            Button(L("Screen Recording", "Ghi màn hình") + (screenOK ? " ✓" : " ✗")) {
                openSettingsOnce(PermissionsChecker.screenRecordingSettingsURL)
            }

            Button(L("Accessibility", "Trợ năng") + (accessOK ? " ✓" : " ✗")) {
                openSettingsOnce(PermissionsChecker.accessibilitySettingsURL)
            }
        }

        Button(launchAtLoginEnabled ? L("Launch at login ✓", "Mở cùng máy ✓") : L("Launch at login", "Mở cùng máy")) {
            toggleLaunchAtLogin()
        }

        Menu("Language / Ngôn ngữ") {
            ForEach(AppLanguage.allCases, id: \.self) { language in
                Button(languageRaw == language.rawValue ? "\(language.displayName) ✓" : language.displayName) {
                    languageRaw = language.rawValue
                }
            }
        }

        Divider()

        Button(L("About Wingpane", "Giới thiệu Wingpane")) {
            let info = Bundle.main.infoDictionary
            NSApp.activate(ignoringOtherApps: true)
            NSApp.orderFrontStandardAboutPanel(options: [
                .applicationName: "Wingpane",
                .applicationVersion: info?["CFBundleShortVersionString"] as? String ?? "",
                .version: info?["CFBundleVersion"] as? String ?? "",
                .credits: NSAttributedString(string: L(Self.aboutTextEN, Self.aboutTextVI)),
                NSApplication.AboutPanelOptionKey(rawValue: "Copyright"): info?["NSHumanReadableCopyright"] as? String ?? "Copyright © 2026 Wingpane. All rights reserved.",
            ])
        }

        Button(L("Quit Wingpane", "Thoát Wingpane")) {
            controller.stop()
            NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q", modifiers: .command)
    }

    private static let aboutTextEN = """
    Turn your Android tablet into a Mac secondary display, pen tablet and trackpad.

    How to use
    1. Plug in the tablet (USB debugging on), open Wingpane on it.
    2. Permissions menu: enable Screen Recording and Accessibility for Wingpane and wingpaned.
    3. Display: tablet is a second screen. Pen: graphics tablet. Trackpad: 1–5 finger gestures (click, scroll, swipes, pinch). Keyboard/Numpad/IME: type into the Mac. Only Display mode keeps the second screen.
    4. Without Accessibility the Mac ignores pen, trackpad and keyboard.

    https://github.com/lamnt9/wingpane
    """

    private static let aboutTextVI = """
    Biến tablet Android thành màn hình phụ, bảng vẽ và trackpad cho Mac.

    Cách dùng
    1. Cắm tablet (bật USB debugging), mở app Wingpane trên tablet.
    2. Menu Quyền: bật Ghi màn hình và Trợ năng cho Wingpane và wingpaned.
    3. Display: màn hình phụ. Pen: bảng vẽ. Trackpad: cử chỉ 1–5 ngón (click, cuộn, vuốt, chụm). Keyboard/Numpad/IME: gõ vào Mac. Chỉ chế độ Display mới giữ màn hình thứ hai.
    4. Thiếu Trợ năng thì Mac bỏ qua bút, trackpad và bàn phím.

    https://github.com/lamnt9/wingpane
    """

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
