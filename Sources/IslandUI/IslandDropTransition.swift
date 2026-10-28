import SwiftUI

/// A vector droplet transition. The same spring/arc is used by the native Android renderer.
struct IslandDropTransitionModifier: AnimatableModifier {
    var progress: CGFloat
    var distance: CGFloat
    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let raw = min(max(progress, 0), 1.08)
        let p = min(raw, 1)
        let wave = sin(raw * .pi)
        let size = 0.78 + 0.22 * p
        let travel = 1 - pow(1 - p, 1.5) + (raw - p) * 0.6
        return content
            .scaleEffect(x: size * (1 + 0.20 * wave), y: size * (1 - 0.12 * wave))
            .background(IslandDropNeck(progress: p, distance: distance).fill(Color.black).allowsHitTesting(false))
            .offset(x: -distance * (1 - travel), y: 6 * wave)
    }
}

private struct IslandDropNeck: Shape {
    var progress: CGFloat
    var distance: CGFloat
    func path(in rect: CGRect) -> Path {
        let p = min(max(progress, 0), 1)
        let travel = 1 - pow(1 - p, 1.5)
        let gap = 8 - distance * (1 - travel)
        let t = min(max((p - 0.55) / 0.41, 0), 1)
        let neck = 1 - t * t * (3 - 2 * t)
        guard p > 0.02, neck > 0.01, gap >= -8, gap <= 36 else { return Path() }
        let half = 11.5 * neck
        let x1 = -gap - 5
        let x2: CGFloat = 3.2
        let middle = (x1 + x2) / 2
        let y1 = rect.midY - 6 * sin(p * .pi)
        let y2 = rect.midY
        var path = Path()
        path.move(to: CGPoint(x: x1, y: y1 - half))
        path.addCurve(to: CGPoint(x: x2, y: y2 - half),
                      control1: CGPoint(x: middle, y: y1 - half * 0.12),
                      control2: CGPoint(x: middle, y: y2 - half * 0.12))
        path.addLine(to: CGPoint(x: x2, y: y2 + half))
        path.addCurve(to: CGPoint(x: x1, y: y1 + half),
                      control1: CGPoint(x: middle, y: y2 + half * 0.12),
                      control2: CGPoint(x: middle, y: y1 + half * 0.12))
        path.closeSubpath()
        return path
    }
}

extension AnyTransition {
    static var islandDrop: AnyTransition {
        .modifier(active: IslandDropTransitionModifier(progress: 0, distance: 42),
                  identity: IslandDropTransitionModifier(progress: 1, distance: 42))
    }
}
