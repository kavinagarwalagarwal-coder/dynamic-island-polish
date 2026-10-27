import SwiftUI

/// One ring and one digit view survive expansion, so their position and size morph together.
public struct TimerIsland: View {
    public var activity: IslandActivity
    public var expanded: Bool
    public var onToggle: (() -> Void)?
    public var onCancel: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(activity: IslandActivity, expanded: Bool,
                onToggle: (() -> Void)? = nil, onCancel: (() -> Void)? = nil) {
        self.activity = activity
        self.expanded = expanded
        self.onToggle = onToggle
        self.onCancel = onCancel
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0,
                                paused: activity.timerRemaining == nil || activity.timerPaused)) { _ in
            GeometryReader { geo in
                let remaining = activity.remainingTime
                let fraction = activity.timerDuration > 0
                    ? min(1, max(0, remaining / activity.timerDuration)) : activity.progress
                ZStack(alignment: .topLeading) {
                    Circle().stroke(activity.accent.opacity(0.22), lineWidth: 2.6)
                        .overlay {
                            Circle().trim(from: 0, to: fraction)
                                .stroke(activity.accent, style: StrokeStyle(lineWidth: 2.6, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                        }
                        .frame(width: expanded ? 22 : 17, height: expanded ? 22 : 17)
                        .position(x: expanded ? 31 : 18, y: expanded ? 60 : geo.size.height / 2)
                    Text(activity.timerRemaining == nil ? activity.subtitle : Self.display(remaining))
                        .font(.system(size: expanded ? 36 : 14, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .foregroundColor(activity.accent)
                        .lineLimit(1).minimumScaleFactor(0.55)
                        .frame(width: expanded ? max(70, geo.size.width - 150) : max(64, geo.size.width / 2 - 20), alignment: .trailing)
                        .position(x: expanded ? geo.size.width - 20 - max(70, geo.size.width - 150) / 2
                                  : geo.size.width - 13 - max(64, geo.size.width / 2 - 20) / 2,
                                  y: expanded ? 60 : geo.size.height / 2)
                        .accessibilityLabel("Time remaining")
                    if expanded {
                        Text(activity.title).font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white).lineLimit(1)
                            .padding(.horizontal, 20).padding(.top, 12)
                        Text(activity.timerPaused ? "Paused" : remaining <= 0 && activity.timerRemaining != nil ? "Time’s up" : "Timer")
                            .font(.system(size: 11)).foregroundColor(.white.opacity(0.65))
                            .offset(x: 54, y: 53)
                        HStack {
                            if let onCancel {
                                Button(action: onCancel) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                                    .background(Circle().fill(Color.white.opacity(0.14)))
                                    .accessibilityLabel("Cancel timer")
                            }
                            Spacer()
                            if let onToggle {
                                Button(action: onToggle) {
                                    Image(systemName: activity.timerPaused ? "play.fill" : "pause.fill")
                                        .frame(width: 44, height: 44)
                                }
                                .background(Circle().fill(activity.accent.opacity(0.22)))
                                .accessibilityLabel(activity.timerPaused ? "Resume timer" : "Pause timer")
                            }
                        }
                        .foregroundColor(activity.accent)
                        .padding(.horizontal, 20).offset(y: 86)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96)))
                    }
                }
            }
        }
    }

    static func display(_ seconds: TimeInterval) -> String {
        let value = Int(ceil(max(0, seconds)))
        let h = value / 3600, m = value % 3600 / 60, s = value % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
