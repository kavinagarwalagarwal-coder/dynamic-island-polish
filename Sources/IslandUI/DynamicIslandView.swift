import SwiftUI

/// The Dynamic Island. Shape (width, height, radius, center) is driven by one
/// interpolating spring so idle ↔ compact ↔ expanded morph instead of jumping.
///
/// Skip compiles this view to Jetpack Compose. Overlay services stay native
/// Android (`IslandOverlayService`) and host the ported Compose/Canvas island.
public struct DynamicIslandView: View {
    public var presentation: IslandPresentation
    public var activity: IslandActivity?
    public var status: StatusInfo
    public var screenWidth: CGFloat
    public var cameraX: CGFloat
    public var top: CGFloat
    public var pillWidth: CGFloat
    public var baseColor: Color
    public var concealed: Bool

    public init(
        presentation: IslandPresentation,
        activity: IslandActivity? = nil,
        status: StatusInfo = StatusInfo(),
        screenWidth: CGFloat,
        cameraX: CGFloat,
        top: CGFloat = 8,
        pillWidth: CGFloat = 1,
        baseColor: Color = Color(red: 0.05, green: 0.05, blue: 0.05),
        concealed: Bool = false
    ) {
        self.presentation = presentation
        self.activity = activity
        self.status = status
        self.screenWidth = screenWidth
        self.cameraX = cameraX
        self.top = top
        self.pillWidth = pillWidth
        self.baseColor = baseColor
        self.concealed = concealed
    }

    private var spec: IslandShapeSpec {
        IslandShapeSpec.spec(
            presentation: presentation,
            activity: activity,
            screenWidth: screenWidth,
            cameraX: cameraX,
            top: top,
            pillWidth: pillWidth
        )
    }

    public var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: spec.radius, style: .continuous)
                .fill(baseColor)
                .frame(width: spec.width, height: spec.height)
                .overlay(content)
                .clipShape(RoundedRectangle(cornerRadius: spec.radius, style: .continuous))
                .offset(x: spec.centerX - spec.width / 2, y: spec.top)
                .opacity(concealed ? 0 : 1)
                .scaleEffect(concealed ? 0.35 : 1, anchor: .top)
        }
        .frame(width: screenWidth, height: spec.top + spec.height + 14, alignment: .topLeading)
        .animation(IslandMotion.shapeAnimation, value: presentation)
        .animation(IslandMotion.shapeAnimation, value: activity?.key)
        .animation(IslandMotion.contentAnimation, value: presentation)
        .animation(.spring(response: 0.32, dampingFraction: 0.9), value: concealed)
        .allowsHitTesting(!concealed && presentation != .idle)
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            IdleStatusBar(status: status, opacity: presentation == .idle ? 1 : 0)
            if let activity {
                CompactIsland(activity: activity, opacity: presentation == .compact ? 1 : 0)
                ExpandedIsland(activity: activity, opacity: presentation == .expanded ? 1 : 0)
            }
        }
    }
}

/// In-app playground used on iOS Simulator / Skip's Android activity.
public struct IslandPlayground: View {
    @State private var presentation: IslandPresentation = .idle
    @State private var mode: IslandMode = .music

    private var activity: IslandActivity? {
        guard presentation != .idle else { return nil }
        return IslandActivity(
            key: mode.rawValue,
            mode: mode,
            title: mode == .music ? "Motion" : mode.rawValue.capitalized,
            subtitle: mode == .music ? "Nothing" : "Live",
            appLabel: mode.rawValue.capitalized,
            accent: accent,
            isPlaying: true
        )
    }

    private var accent: Color {
        switch mode {
        case .message: return Color(red: 0.15, green: 0.76, blue: 0.38)
        case .call: return .green
        case .music: return Color(red: 0.95, green: 0.35, blue: 0.35)
        case .charging: return .green
        case .timer: return Color(red: 1, green: 0.62, blue: 0.18)
        case .navigation: return Color(red: 0.3, green: 0.6, blue: 1)
        }
    }

    public init() {}

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                Color.black.ignoresSafeArea()
                DynamicIslandView(
                    presentation: presentation,
                    activity: activity,
                    screenWidth: geo.size.width,
                    cameraX: geo.size.width / 2
                )
                VStack(spacing: 12) {
                    Spacer()
                    Text("Dynamic Droid")
                        .foregroundColor(.white.opacity(0.6))
                    HStack {
                        chip("Idle", .idle)
                        chip("Compact", .compact)
                        chip("Expanded", .expanded)
                    }
                    HStack {
                        ForEach([IslandMode.music, .call, .timer, .message], id: \.self) { m in
                            Button(m.rawValue) { mode = m }
                                .font(.caption)
                                .foregroundColor(.white.opacity(mode == m ? 1 : 0.45))
                        }
                    }
                    .padding(.bottom, 28)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func chip(_ title: String, _ value: IslandPresentation) -> some View {
        Button(title) { presentation = value }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Capsule().fill(Color.white.opacity(presentation == value ? 0.2 : 0.06)))
            .foregroundColor(.white)
    }
}
