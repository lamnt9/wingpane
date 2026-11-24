// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WingpaneMacApp",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "WingpaneAppCore", targets: ["WingpaneAppCore"]),
        .executable(name: "Wingpane", targets: ["Wingpane"]),
    ],
    targets: [
        .target(
            name: "WingpaneAppCore",
            linkerSettings: [
                .linkedFramework("Foundation"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("ApplicationServices"),
            ]
        ),
        .executableTarget(
            name: "Wingpane",
            dependencies: ["WingpaneAppCore"],
            linkerSettings: [
                .linkedFramework("SwiftUI"),
                .linkedFramework("AppKit"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("ApplicationServices"),
            ]
        ),
        .testTarget(
            name: "WingpaneAppCoreTests",
            dependencies: ["WingpaneAppCore"]
        ),
    ]
)
