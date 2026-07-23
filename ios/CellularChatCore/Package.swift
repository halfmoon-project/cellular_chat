// swift-tools-version:6.2
import PackageDescription

// Note: PROTOCOL_V2.md targets iOS 26. `.iOS(.v26)` is only available from
// PackageDescription 6.2, so the tools version is 6.2 (the installed toolchain
// is Swift 6.2.3). macOS is present only so `swift test` runs on this machine.
let package = Package(
    name: "CellularChatCore",
    platforms: [.iOS(.v26), .macOS(.v14)],
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
