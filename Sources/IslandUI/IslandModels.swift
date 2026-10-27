import SwiftUI

public enum IslandPresentation: String, Equatable {
    case idle
    case compact
    case expanded
}

public enum IslandMode: String, Equatable {
    case music, call, timer, navigation, charging, message
}

public struct StatusInfo: Equatable {
    public var time: String
    public var battery: Int
    public var charging: Bool
    public var bars: Int
    public var wifi: Bool

    public init(time: String = "9:41", battery: Int = 80, charging: Bool = false, bars: Int = 3, wifi: Bool = true) {
        self.time = time
        self.battery = battery
        self.charging = charging
        self.wifi = wifi
        self.bars = bars
    }
}

public struct IslandActivity: Equatable, Identifiable {
    public var id: String { key }
    public var key: String
    public var packageName: String
    public var mode: IslandMode
    public var title: String
    public var subtitle: String
    public var appLabel: String
    public var accent: Color
    public var isPlaying: Bool
    public var progress: Double
    public var timerRemaining: TimeInterval?
    public var timerDuration: TimeInterval
    public var timerPaused: Bool
    public var timerUpdatedAt: TimeInterval

    public var remainingTime: TimeInterval {
        guard let timerRemaining else { return 0 }
        return max(0, timerRemaining - (timerPaused ? 0 : max(0, ProcessInfo.processInfo.systemUptime - timerUpdatedAt)))
    }

    public init(
        key: String,
        packageName: String = "",
        mode: IslandMode,
        title: String,
        subtitle: String,
        appLabel: String,
        accent: Color,
        isPlaying: Bool = false,
        progress: Double = 0.4,
        timerRemaining: TimeInterval? = nil,
        timerDuration: TimeInterval = 0,
        timerPaused: Bool = false,
        timerUpdatedAt: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) {
        self.key = key
        self.packageName = packageName
        self.mode = mode
        self.title = title
        self.subtitle = subtitle
        self.appLabel = appLabel
        self.accent = accent
        self.isPlaying = isPlaying
        self.progress = progress
        self.timerRemaining = timerRemaining
        self.timerDuration = timerDuration
        self.timerPaused = timerPaused
        self.timerUpdatedAt = timerUpdatedAt
    }
}

public struct IslandShapeSpec: Equatable {
    public var width: CGFloat
    public var height: CGFloat
    public var radius: CGFloat
    public var centerX: CGFloat
    public var top: CGFloat

    public static func spec(
        presentation: IslandPresentation,
        activity: IslandActivity?,
        screenWidth: CGFloat,
        cameraX: CGFloat,
        top: CGFloat,
        pillWidth: CGFloat = 1
    ) -> IslandShapeSpec {
        let margin = IslandMotion.idleSideMargin
        let maxWidth = max(0, screenWidth - margin * 2)
        let screenCenter = screenWidth / 2
        if presentation == .idle || activity == nil {
            let h = IslandMotion.idleHeight
            return IslandShapeSpec(width: maxWidth, height: h, radius: h / 2, centerX: screenCenter, top: top)
        }
        if presentation == .compact {
            let w = min(IslandMotion.compactWidth * pillWidth, maxWidth)
            let h = IslandMotion.compactHeight
            let center = min(max(cameraX, margin + w / 2), max(margin + w / 2, screenWidth - margin - w / 2))
            return IslandShapeSpec(width: w, height: h, radius: h / 2, centerX: center, top: top)
        }
        let w = maxWidth
        let h: CGFloat
        switch activity?.mode {
        case .music: h = 178
        case .call: h = 88
        case .message: h = 134
        case .charging: h = 64
        case .timer: h = 138
        default: h = 84
        }
        return IslandShapeSpec(width: w, height: h, radius: min(46, h / 2), centerX: screenCenter, top: top)
    }
}
