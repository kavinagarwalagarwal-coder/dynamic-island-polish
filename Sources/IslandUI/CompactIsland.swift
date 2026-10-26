import SwiftUI

public struct CompactIsland: View {
    public var activity: IslandActivity
    public var opacity: Double

    public init(activity: IslandActivity, opacity: Double = 1) {
        self.activity = activity
        self.opacity = opacity
    }

    public var body: some View {
        HStack(spacing: 8) {
            leading
            Spacer(minLength: 4)
            trailing
        }
        .padding(.horizontal, 10)
        .opacity(opacity)
    }

    @ViewBuilder
    private var leading: some View {
        switch activity.mode {
        case .music:
            ArtworkDot(accent: activity.accent)
        case .call:
            Image(systemName: "phone.fill")
                .foregroundColor(.green)
        case .timer:
            Image(systemName: "timer")
                .foregroundColor(activity.accent)
        case .charging:
            Image(systemName: "battery.100.bolt")
                .foregroundColor(.green)
        case .message:
            Image(systemName: "message.fill")
                .foregroundColor(activity.accent)
        case .navigation:
            Image(systemName: "location.fill")
                .foregroundColor(activity.accent)
        }
    }

    @ViewBuilder
    private var trailing: some View {
        switch activity.mode {
        case .music:
            Image(systemName: activity.isPlaying ? "waveform" : "pause.fill")
                .foregroundColor(activity.accent)
        case .call:
            Text("0:12")
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundColor(.green)
        case .timer:
            Text(activity.subtitle)
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundColor(activity.accent)
        default:
            Text(activity.title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.white)
                .lineLimit(1)
        }
    }
}

struct ArtworkDot: View {
    var accent: Color
    var body: some View {
        RoundedRectangle(cornerRadius: 6)
            .fill(accent.opacity(0.85))
            .frame(width: 22, height: 22)
    }
}
