// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WingpaneDisplay",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "WingpaneDisplayKit", targets: ["WingpaneDisplayKit"]),
        .executable(name: "wp-vdisplay", targets: ["wp-vdisplay"]),
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
        .executableTarget(
            name: "wp-vdisplay",
            dependencies: ["WingpaneDisplayKit"]
        ),
    ]
)
