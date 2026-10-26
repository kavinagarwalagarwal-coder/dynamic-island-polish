// swift-tools-version: 5.7
import PackageDescription

let package = Package(
    name: "IslandUI",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "IslandUI", targets: ["IslandUI"]),
    ],
    targets: [
        .target(
            name: "IslandUI",
            path: "Sources/IslandUI"
        ),
    ]
)
