import CoreGraphics
import Dispatch
import Foundation
import WingpaneDisplayKit

struct Options {
    var width = 2560
    var height = 1600
    var hz = 60.0
    var hiDPI = false
    var name = "Wingpane"
    var seconds: Double?
}

func usage() -> String {
    """
    Usage: wp-vdisplay [--width N] [--height N] [--hz N] [--hidpi] [--name TEXT] [--seconds N]
    """
}

func parseOptions(_ arguments: [String]) throws -> Options {
    var options = Options()
    var index = 0
    while index < arguments.count {
        let argument = arguments[index]
        if argument == "--hidpi" {
            options.hiDPI = true
            index += 1
            continue
        }
        guard index + 1 < arguments.count else {
            throw CLIError(message: "missing value for \(argument)")
        }
        let value = arguments[index + 1]
        switch argument {
        case "--width":
            guard let parsed = Int(value), parsed > 0 else { throw CLIError(message: "invalid --width") }
            options.width = parsed
        case "--height":
            guard let parsed = Int(value), parsed > 0 else { throw CLIError(message: "invalid --height") }
            options.height = parsed
        case "--hz":
            guard let parsed = Double(value), parsed.isFinite, parsed > 0 else { throw CLIError(message: "invalid --hz") }
            options.hz = parsed
        case "--name":
            options.name = value
        case "--seconds":
            guard let parsed = Double(value), parsed.isFinite, parsed > 0 else { throw CLIError(message: "invalid --seconds") }
            options.seconds = parsed
        case "--help", "-h":
            print(usage())
            exit(0)
        default:
            throw CLIError(message: "unknown option \(argument)")
        }
        index += 2
    }
    return options
}

struct CLIError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

func onlineDisplayCount() -> UInt32 {
    var count: UInt32 = 0
    let result = CGGetOnlineDisplayList(0, nil, &count)
    guard result == .success else { return 0 }
    return count
}

func describeAppliedMode(for displayID: CGDirectDisplayID) -> String {
    guard let mode = CGDisplayCopyDisplayMode(displayID) else { return "Unknown" }
    let width = mode.width
    let height = mode.height
    let refresh = mode.refreshRate
    let pixelWidth = mode.pixelWidth
    let pixelHeight = mode.pixelHeight
    return "\(width)x\(height) logical, \(pixelWidth)x\(pixelHeight) pixels @ \(refresh) Hz"
}

func run() throws {
    let options = try parseOptions(Array(CommandLine.arguments.dropFirst()))
    let before = onlineDisplayCount()
    print("Online displays before: \(before)")

    let display = try VirtualDisplay(
        width: options.width,
        height: options.height,
        refreshHz: options.hz,
        hiDPI: options.hiDPI,
        name: options.name
    )
    defer { display.close() }

    let after = onlineDisplayCount()
    print("Display ID: \(display.displayID)")
    print("Applied mode: \(describeAppliedMode(for: display.displayID))")
    print("Online displays after: \(after)")
    print("Online display count delta: \(Int(after) - Int(before))")

    signal(SIGINT, SIG_IGN)
    let interruptSemaphore = DispatchSemaphore(value: 0)
    let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .global())
    interrupt.setEventHandler { interruptSemaphore.signal() }
    interrupt.resume()
    defer { interrupt.cancel() }

    if let seconds = options.seconds {
        _ = interruptSemaphore.wait(timeout: .now() + seconds)
    } else {
        interruptSemaphore.wait()
    }
    display.close()
    let teardownDeadline = Date().addingTimeInterval(2)
    var afterTeardown = onlineDisplayCount()
    while afterTeardown > before, Date() < teardownDeadline {
        Thread.sleep(forTimeInterval: 0.05)
        afterTeardown = onlineDisplayCount()
    }
    print("Online displays after teardown: \(afterTeardown)")
}

do {
    try run()
} catch {
    fputs("wp-vdisplay: \(error)\n\(usage())\n", stderr)
    exit(1)
}
