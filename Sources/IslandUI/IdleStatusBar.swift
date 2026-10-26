import SwiftUI

/// Full-width idle island: time on the left, network + battery on the right.
public struct IdleStatusBar: View {
    public var status: StatusInfo
    public var opacity: Double

    public init(status: StatusInfo, opacity: Double = 1) {
        self.status = status
        self.opacity = opacity
    }

    public var body: some View {
        HStack(spacing: 10) {
            Text(status.time)
                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                .monospacedDigit()
            Spacer(minLength: 8)
            StatusCluster(status: status)
        }
        .foregroundColor(Color.white.opacity(0.94))
        .padding(.horizontal, 16)
        .opacity(opacity)
    }
}

public struct StatusCluster: View {
    public var status: StatusInfo

    public var body: some View {
        HStack(spacing: 8) {
            if status.wifi {
                WifiGlyph(bars: status.bars)
            } else {
                CellularGlyph(bars: status.bars)
            }
            BatteryGlyph(level: status.battery, charging: status.charging)
        }
    }
}

struct WifiGlyph: View {
    var bars: Int
    var body: some View {
        ZStack(alignment: .bottom) {
            ForEach(1..<4, id: \.self) { i in
                Image(systemName: "wifi")
                    .font(.system(size: CGFloat(7 + i * 3), weight: .semibold))
                    .opacity(bars >= i ? 1 : 0.22)
            }
        }
        .frame(width: 16, height: 12)
        .mask(Rectangle().frame(height: 12))
    }
}

struct CellularGlyph: View {
    var bars: Int
    var body: some View {
        HStack(alignment: .bottom, spacing: 1.5) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 0.8)
                    .frame(width: 2.6, height: CGFloat(4 + i * 2.4))
                    .opacity(bars > i ? 1 : 0.22)
            }
        }
        .frame(height: 12)
    }
}

struct BatteryGlyph: View {
    var level: Int
    var charging: Bool
    var body: some View {
        HStack(spacing: 1.5) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .stroke(Color.white.opacity(0.55), lineWidth: 1.2)
                    .frame(width: 22, height: 11)
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(fillColor)
                    .frame(width: max(2, 16.5 * CGFloat(max(level, 0)) / 100), height: 7)
                    .padding(.leading, 2.2)
                if charging {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                }
            }
            Capsule()
                .fill(Color.white.opacity(0.55))
                .frame(width: 1.6, height: 4.4)
        }
    }

    private var fillColor: Color {
        if charging { return Color(red: 0.62, green: 0.62, blue: 0.62) }
        if level <= 15 { return Color(red: 0.92, green: 0.19, blue: 0.19) }
        return Color.white.opacity(0.94)
    }
}
