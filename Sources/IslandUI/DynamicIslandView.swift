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
    public var secondaryActivity: IslandActivity?
    public var onOpen: ((IslandActivity) -> Void)?
    public var onExpand: ((IslandActivity) -> Void)?
    public var onCollapse: (() -> Void)?
    @State private var pressed = false
    @State private var dragOffset: CGFloat = 0
    public var concealed: Bool
    public var onTimerToggle: (() -> Void)?
    public var onTimerCancel: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(
        presentation: IslandPresentation,
        activity: IslandActivity? = nil,
        status: StatusInfo = StatusInfo(),
        screenWidth: CGFloat,
        cameraX: CGFloat,
        top: CGFloat = 8,
        pillWidth: CGFloat = 1,
        baseColor: Color = Color(red: 0.05, green: 0.05, blue: 0.05),
        concealed: Bool = false,
        onTimerToggle: (() -> Void)? = nil,
        onTimerCancel: (() -> Void)? = nil,
        secondaryActivity: IslandActivity? = nil,
        onOpen: ((IslandActivity) -> Void)? = nil,
        onExpand: ((IslandActivity) -> Void)? = nil,
        onCollapse: (() -> Void)? = nil
    ) {
        self.secondaryActivity = secondaryActivity
        self.onOpen = onOpen
        self.onExpand = onExpand
        self.onCollapse = onCollapse
        self.presentation = presentation
        self.activity = activity
        self.status = status
        self.screenWidth = screenWidth
        self.cameraX = cameraX
        self.top = top
        self.pillWidth = pillWidth
        self.baseColor = baseColor
        self.concealed = concealed
        self.onTimerToggle = onTimerToggle
        self.onTimerCancel = onTimerCancel
    }

    private var spec: IslandShapeSpec {
        let shape = IslandShapeSpec.spec(
            presentation: presentation,
            activity: activity,
            screenWidth: screenWidth,
            cameraX: cameraX,
            top: top,
            pillWidth: pillWidth
        )
        guard presentation == .compact, secondaryActivity != nil else { return shape }
        let width = min(shape.width, max(0, screenWidth - 52))
        let center = min(max(shape.centerX, 4 + width / 2), max(4 + width / 2, screenWidth - 48 - width / 2))
        return IslandShapeSpec(width: width, height: shape.height, radius: shape.radius, centerX: center, top: shape.top)
    }

    public var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: spec.radius, style: .continuous)
                .fill(Color.black)
                .frame(width: spec.width, height: spec.height)
                .contentShape(RoundedRectangle(cornerRadius: spec.radius, style: .continuous))
                .onTapGesture { if let activity { onOpen?(activity) } }
                .onLongPressGesture(minimumDuration: 0.35, maximumDistance: 12, pressing: { pressing in
                    pressed = pressing
                }) {
                    if let activity { onExpand?(activity) }
                }
                .simultaneousGesture(DragGesture(minimumDistance: 8)
                    .onChanged { value in
                        dragOffset = reduceMotion ? 0 : 24 * tanh(value.translation.height / 100)
                    }
                    .onEnded { value in
                        dragOffset = 0; pressed = false
                        if abs(value.translation.height) > abs(value.translation.width) {
                            if value.translation.height < -26 { onCollapse?() }
                            else if value.translation.height > 26, let activity { onExpand?(activity) }
                        }
                    })
                .overlay(content)
                .clipShape(RoundedRectangle(cornerRadius: spec.radius, style: .continuous))
                .offset(x: spec.centerX - spec.width / 2, y: spec.top)
                .opacity(concealed ? 0 : 1)
                .scaleEffect(concealed ? 0.35 : pressed && !reduceMotion ? 0.96 : 1, anchor: .top)
                .offset(y: dragOffset)
            if presentation == .compact, let secondaryActivity {
                Circle().fill(Color.black)
                    .overlay {
                        Image(systemName: secondarySymbol(secondaryActivity.mode))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(secondaryActivity.accent)
                    }
                    .frame(width: 36, height: 36)
                    .contentShape(Circle())
                    .onTapGesture { onOpen?(secondaryActivity) }
                    .onLongPressGesture(minimumDuration: 0.35) { onExpand?(secondaryActivity) }
                    .accessibilityLabel("\(secondaryActivity.appLabel): \(secondaryActivity.title)")
                    .offset(x: spec.centerX + spec.width / 2 + 8, y: spec.top)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .frame(width: screenWidth, height: spec.top + spec.height + 14, alignment: .topLeading)
        .animation(reduceMotion ? .easeOut(duration: 0.15) : IslandMotion.shapeAnimation, value: presentation)
        .animation(reduceMotion ? .easeOut(duration: 0.15) : IslandMotion.shapeAnimation, value: activity?.key)
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.32, dampingFraction: 0.9), value: concealed)
        .animation(reduceMotion ? .easeOut(duration: 0.15) : IslandMotion.shapeAnimation, value: secondaryActivity?.key)
        .animation(reduceMotion ? nil : .spring(response: 0.18, dampingFraction: 0.72), value: pressed)
        .animation(reduceMotion ? nil : IslandMotion.contentAnimation, value: dragOffset)
        .allowsHitTesting(!concealed && presentation != .idle)
    }

    private func secondarySymbol(_ mode: IslandMode) -> String {
        switch mode {
        case .timer: return "timer"
        case .music: return "waveform"
        case .call: return "phone.fill"
        case .navigation: return "location.fill"
        case .charging: return "bolt.fill"
        case .message: return "message.fill"
        }
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            IdleStatusBar(status: status, opacity: presentation == .idle ? 1 : 0)
            if let activity {
                if activity.mode == .timer {
                    TimerIsland(activity: activity, expanded: presentation == .expanded,
                                onToggle: onTimerToggle, onCancel: onTimerCancel)
                        .opacity(presentation == .idle ? 0 : 1)
                } else {
                    CompactIsland(activity: activity, opacity: presentation == .compact ? 1 : 0)
                    ExpandedIsland(activity: activity, opacity: presentation == .expanded ? 1 : 0)
                }
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
