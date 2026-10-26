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

    public init(
        key: String,
        packageName: String = "",
        mode: IslandMode,
        title: String,
        subtitle: String,
        appLabel: String,
        accent: Color,
        isPlaying: Bool = false,
        progress: Double = 0.4
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
            return IslandShapeSpec(width: w, height: h, radius: h / 2, centerX: cameraX, top: top)
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
