import SwiftUI

/// The live Home handoff only grows the island at its calibrated position.
/// Android owns the outgoing app window; drawing a second dashboard here would
/// create a disconnected miniature window after the launcher transition.
public struct IslandReturnEntrance: View {
    private let pillWidth: CGFloat
    private let pillHeight: CGFloat
    private let centerX: CGFloat
    private let centerY: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var arrived = false

    public init(width: Double, height: Double, centerX: Double, centerY: Double) {
        self.pillWidth = width
        self.pillHeight = height
        self.centerX = centerX
        self.centerY = centerY
    }

    public var body: some View {
        GeometryReader { _ in
            Capsule()
                .fill(Color.black)
                .frame(
                    width: arrived ? pillWidth : min(40.0, pillWidth),
                    height: arrived ? pillHeight : min(14.0, pillHeight)
                )
                .position(x: centerX, y: centerY)
                .opacity(arrived ? 1.0 : 0.0)
        }
        .background(Color.clear)
        .ignoresSafeArea()
        .onAppear {
            withAnimation(reduceMotion ? .easeOut(duration: 0.12) :
                .interpolatingSpring(stiffness: 260, damping: 30)) {
                arrived = true
            }
        }
    }
}
