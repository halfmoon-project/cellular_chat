// swift-tools-version:6.2
import PackageDescription

// Note: the app deploys to iOS 18; Wi-Fi Aware (iOS 26) is availability-gated
// in the app layer. Tools version 6.2 matches the installed toolchain (Swift
// 6.2.3). macOS is present only so `swift test` runs on this machine.
let package = Package(
    name: "CellularChatCore",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "CellularChatCore", targets: ["CellularChatCore"]),
    ],
    targets: [
        .target(name: "CellularChatCore"),
        .testTarget(
            name: "CellularChatCoreTests",
            dependencies: ["CellularChatCore"]
        ),
    ]
)
