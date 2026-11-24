// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WingpaneDisplay",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "WingpaneDisplayKit", targets: ["WingpaneDisplayKit"]),
        .library(name: "WingpaneCaptureKit", targets: ["WingpaneCaptureKit"]),
        .executable(name: "wp-vdisplay", targets: ["wp-vdisplay"]),
        .executable(name: "wp-capture", targets: ["wp-capture"]),
    ],
    targets: [
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
            .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo")
        ]),
        .executableTarget(
            name: "wp-vdisplay",
            dependencies: ["WingpaneDisplayKit"]
        ),
        .executableTarget(name: "wp-capture", dependencies: ["WingpaneDisplayKit", "WingpaneCaptureKit"], linkerSettings: [
            .linkedFramework("AppKit"), .linkedFramework("ScreenCaptureKit")
        ]),
    ]
)
