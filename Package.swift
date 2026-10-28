// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "IslandUI",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "IslandUI", targets: ["IslandUI"]),
        .library(name: "IslandMorphUI", targets: ["IslandMorphUI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/skiptools/skip.git", from: "1.7.0"),
        .package(url: "https://github.com/skiptools/skip-ui.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "IslandMorphUI",
            dependencies: [.product(name: "SkipUI", package: "skip-ui")],
            path: "Sources/IslandMorphUI",
            plugins: [.plugin(name: "skipstone", package: "skip")]
        ),
        .target(
            name: "IslandUI",
            dependencies: ["IslandMorphUI"],
            path: "Sources/IslandUI"
        ),
        .testTarget(name: "IslandUITests", dependencies: ["IslandUI"], path: "Tests/IslandUITests"),
    ]
)
