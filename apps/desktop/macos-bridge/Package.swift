// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WingpaneDisplay",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "WingpaneDisplayKit", targets: ["WingpaneDisplayKit"]),
        .library(name: "WingpaneCaptureKit", targets: ["WingpaneCaptureKit"]),
        .library(name: "WingpaneBridge", type: .static, targets: ["WingpaneBridge"]),
        .library(name: "WingpaneUcSupport", targets: ["WingpaneUcSupport"]),
        .executable(name: "wp-vdisplay", targets: ["wp-vdisplay"]),
        .executable(name: "wp-capture", targets: ["wp-capture"]),
        .executable(name: "wp-uc-selftest", targets: ["wp-uc-selftest"]),
    ],
    targets: [
        .target(name: "WingpaneUcSupport"),
        .target(
            name: "CGVirtualDisplayPrivate",
            path: "Sources/CGVirtualDisplayPrivate",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("CoreGraphics"),
                .linkedFramework("Foundation"),
            ]
        ),
        .target(
            name: "WingpaneDisplayKit",
            dependencies: ["CGVirtualDisplayPrivate"],
            linkerSettings: [.linkedFramework("CoreGraphics")]
        ),
        .target(name: "WingpaneCaptureKit", linkerSettings: [
            .linkedFramework("ScreenCaptureKit"), .linkedFramework("VideoToolbox"),
            .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo"), .linkedFramework("AppKit")
        ]),
        .target(name: "WingpaneBridge", dependencies: ["WingpaneDisplayKit", "WingpaneCaptureKit", "WingpaneUcSupport"], linkerSettings: [
            .linkedFramework("ScreenCaptureKit"), .linkedFramework("VideoToolbox"),
            .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo"),
            .linkedFramework("CoreGraphics"), .linkedFramework("Foundation"), .linkedFramework("AppKit")
        ]),
        .executableTarget(
            name: "wp-vdisplay",
            dependencies: ["WingpaneDisplayKit"]
        ),
        .executableTarget(name: "wp-capture", dependencies: ["WingpaneDisplayKit", "WingpaneCaptureKit"], linkerSettings: [
            .linkedFramework("AppKit"), .linkedFramework("ScreenCaptureKit")
        ]),
        .executableTarget(name: "wp-uc-selftest", dependencies: ["WingpaneBridge"]),
        .testTarget(name: "WingpaneBridgeTests", dependencies: ["WingpaneUcSupport"]),
    ]
)
