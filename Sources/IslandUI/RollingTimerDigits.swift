import SwiftUI

/// Tabular figures with independent, clipped vertical transitions for each changed digit.
struct RollingTimerDigits: View {
    var value: String
    var size: CGFloat
    var color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(value.enumerated()), id: \.offset) { index, character in
                ZStack {
                    Text(String(character))
                        .id("\(index)-\(character)")
                        .transition(reduceMotion ? .opacity : .asymmetric(
                            insertion: .move(edge: .top).combined(with: .opacity),
                            removal: .move(edge: .bottom).combined(with: .opacity)))
                }
                .frame(width: size * (character == ":" ? 0.28 : 0.62), height: size * 1.25)
                .clipped()
            }
        }
        .font(.system(size: size, weight: .medium, design: .rounded).monospacedDigit())
        .foregroundColor(color)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.24), value: value)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(value)
    }
}
