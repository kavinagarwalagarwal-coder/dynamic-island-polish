import XCTest
import SwiftUI
@testable import IslandUI

final class IslandUITests: XCTestCase {
    private func timer(paused: Bool = false, remaining: TimeInterval = 60) -> IslandActivity {
        IslandActivity(key: "timer", mode: .timer, title: "Tea", subtitle: "Timer", appLabel: "Clock",
                       accent: .orange, timerRemaining: remaining, timerDuration: 60,
                       timerPaused: paused, timerUpdatedAt: ProcessInfo.processInfo.systemUptime - 2)
    }
    func testCountdownDoesNotDisplayZeroEarly() {
        XCTAssertEqual(TimerIsland.display(0.001), "0:01")
        XCTAssertEqual(TimerIsland.display(59.001), "1:00")
        XCTAssertEqual(TimerIsland.display(3600.001), "1:00:01")
        XCTAssertEqual(TimerIsland.display(-1), "0:00")
    }
    func testPausedAndMonotonicCountdown() {
        XCTAssertEqual(timer(paused: true).remainingTime, 60)
        XCTAssertLessThanOrEqual(timer().remainingTime, 58)
        XCTAssertGreaterThan(timer().remainingTime, 57)
        XCTAssertEqual(timer(remaining: 1).remainingTime, 0)
    }
    func testIdleContractsIntoCameraInsteadOfStatusBar() {
        let shape = IslandShapeSpec.spec(presentation: .idle, activity: nil, screenWidth: 360,
                                         cameraX: 83, top: 8)
        XCTAssertEqual(shape.width, 14)
        XCTAssertEqual(shape.height, 14)
        XCTAssertEqual(shape.centerX, 83)
        XCTAssertEqual(shape.top + shape.height / 2, 26)
    }
    func testCompactStaysInsideNarrowScreens() {
        for width in [CGFloat(240), 320, 360] {
            for camera in [CGFloat(-180), width / 2, width + 180] {
                let shape = IslandShapeSpec.spec(presentation: .compact, activity: timer(), screenWidth: width,
                                                 cameraX: camera, top: 8, pillWidth: 1.5)
                XCTAssertGreaterThanOrEqual(shape.centerX - shape.width / 2, 1.99)
                XCTAssertLessThanOrEqual(shape.centerX + shape.width / 2, width - 1.99)
            }
        }
    }
}
