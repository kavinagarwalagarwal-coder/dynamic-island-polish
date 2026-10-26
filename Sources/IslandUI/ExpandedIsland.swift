import SwiftUI

public struct ExpandedIsland: View {
    public var activity: IslandActivity
    public var opacity: Double

    public init(activity: IslandActivity, opacity: Double = 1) {
        self.activity = activity
        self.opacity = opacity
    }

    public var body: some View {
        Group {
            switch activity.mode {
            case .music: music
            case .call: call
            case .timer: timer
            case .charging: charging
            case .message: message
            case .navigation: generic
            }
        }
        .padding(16)
        .opacity(opacity)
        .scaleEffect(0.94 + 0.06 * opacity, anchor: .top)
        .offset(y: (1 - opacity) * -10)
    }

    private var music: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(activity.accent.opacity(0.9))
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(activity.title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text(activity.subtitle)
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.55))
                        .lineLimit(1)
                }
                Spacer()
            }
            Capsule()
                .fill(Color.white.opacity(0.12))
                .frame(height: 4)
                .overlay(alignment: .leading) {
                    GeometryReader { geo in
                        Capsule()
                            .fill(activity.accent)
                            .frame(width: geo.size.width * activity.progress)
                    }
                }
            HStack {
                Spacer()
                Image(systemName: "backward.fill")
                Spacer()
                Image(systemName: activity.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 22))
                Spacer()
                Image(systemName: "forward.fill")
                Spacer()
            }
            .foregroundColor(.white)
            .font(.system(size: 16))
        }
    }

    private var call: some View {
        HStack {
            Image(systemName: "phone.fill")
                .font(.system(size: 20))
                .foregroundColor(.green)
            VStack(alignment: .leading) {
                Text(activity.title).foregroundColor(.white).font(.headline)
                Text(activity.subtitle).foregroundColor(.white.opacity(0.55)).font(.subheadline)
            }
            Spacer()
            Circle().fill(Color.red).frame(width: 36, height: 36)
                .overlay(Image(systemName: "phone.down.fill").foregroundColor(.white))
            Circle().fill(Color.green).frame(width: 36, height: 36)
                .overlay(Image(systemName: "phone.fill").foregroundColor(.white))
        }
    }

    private var timer: some View {
        HStack {
            ZStack {
                Circle().stroke(Color.white.opacity(0.12), lineWidth: 5)
                Circle()
                    .trim(from: 0, to: activity.progress)
                    .stroke(activity.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 44, height: 44)
            VStack(alignment: .leading) {
                Text(activity.title).foregroundColor(.white).font(.headline)
                Text(activity.subtitle)
                    .font(.system(size: 22, weight: .semibold, design: .monospaced))
                    .foregroundColor(.white)
            }
            Spacer()
        }
    }

    private var charging: some View {
        HStack {
            Image(systemName: "battery.100.bolt").foregroundColor(.green)
            Text(activity.title).foregroundColor(.white).font(.headline)
            Spacer()
            Text("Charging").foregroundColor(.white.opacity(0.55))
        }
    }

    private var message: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(activity.accent).frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(activity.appLabel).foregroundColor(activity.accent).font(.caption.weight(.semibold))
                Text(activity.title).foregroundColor(.white).font(.headline)
                Text(activity.subtitle).foregroundColor(.white.opacity(0.6)).font(.subheadline).lineLimit(2)
            }
            Spacer()
        }
    }

    private var generic: some View {
        HStack {
            Circle().fill(activity.accent).frame(width: 36, height: 36)
            VStack(alignment: .leading) {
                Text(activity.title).foregroundColor(.white).font(.headline)
                Text(activity.subtitle).foregroundColor(.white.opacity(0.55))
            }
            Spacer()
        }
    }
}
