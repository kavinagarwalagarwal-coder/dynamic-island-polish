import SwiftUI

/// A SwiftUI app-to-island morph prototype. The app surface, its contents, and the
/// compact island are animated together so the whole screen appears to contract
/// into the island instead of simply switching between two unrelated screens.
public struct AppIslandMorphDemo: View {
    private let onClose: () -> Void
    private let trackTitle: String
    private let trackSubtitle: String
    private let appLabel: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isCompact = false

    public init(
        title: String = "Midnight City",
        subtitle: String = "M83 · Hurry Up, We're Dreaming",
        appLabel: String = "Music",
        onClose: @escaping () -> Void = {}
    ) {
        self.trackTitle = title
        self.trackSubtitle = subtitle
        self.appLabel = appLabel
        self.onClose = onClose
    }

    public var body: some View {
        GeometryReader { geometry in
            let screen = geometry.size
            let progress: CGFloat = isCompact ? 1.0 : 0.0
            let pillWidth = min(150.0, max(118.0, screen.width - 32.0))
            let pillHeight: CGFloat = 40
            let shellWidth = screen.width + (pillWidth - screen.width) * progress
            let shellHeight = screen.height + (pillHeight - screen.height) * progress
            let centerY = screen.height / 2 + (28 - screen.height / 2) * progress
            let cornerRadius = 32.0 + (20.0 - 32.0) * progress

            ZStack {
                Color.black.ignoresSafeArea()

                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color(red: 0.08, green: 0.12, blue: 0.18), Color.black],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    dashboard(size: screen)
                        .frame(width: screen.width, height: screen.height, alignment: .topLeading)
                        // Keep the full app layout alive while scaling it into the
                        // changing shell. The center anchor makes it converge on
                        // the camera/island position as the shell moves upward.
                        .scaleEffect(
                            x: shellWidth / max(screen.width, 1.0),
                            y: shellHeight / max(screen.height, 1),
                            anchor: .center
                        )
                        .opacity(1 - min(progress * 1.18, 1))
                        .frame(width: shellWidth, height: shellHeight)

                    islandPill(width: pillWidth, height: pillHeight)
                        .frame(width: shellWidth, height: shellHeight, alignment: .center)
                        .opacity(progress)
                        .animation(.easeIn(duration: 0.2).delay(0.06), value: isCompact)
                }
                .frame(width: shellWidth, height: shellHeight, alignment: .topLeading)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .position(x: screen.width / 2, y: centerY)
                .animation(
                    reduceMotion ? .easeOut(duration: 0.16) : .interpolatingSpring(stiffness: 205, damping: 23.77),
                    value: isCompact
                )

                Button("Close preview", action: onClose)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white.opacity(0.75))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(Color.white.opacity(0.08), in: Capsule())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 24)
            }
            .onAppear {
                // Opening the demo mimics leaving a media app: its full-screen
                // surface contracts into the island. Tapping the pill reverses it.
                withAnimation(reduceMotion ? .easeOut(duration: 0.16) : .interpolatingSpring(stiffness: 205, damping: 23.77)) {
                    isCompact = true
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
    }

    private func dashboard(size: CGSize) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Now playing · \(appLabel)")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white.opacity(0.6))
                    Text(trackTitle)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                    Text(trackSubtitle)
                        .font(.system(size: 15))
                        .foregroundColor(.white.opacity(0.62))
                }
                Spacer(minLength: 8)
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color(red: 0.36, green: 0.28, blue: 0.74), Color(red: 0.92, green: 0.42, blue: 0.35)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 74, height: 74)
                    .overlay(Image(systemName: "waveform").font(.system(size: 28, weight: .medium)).foregroundColor(.white))
            }
            .padding(.top, 74)

            HStack(spacing: 8) {
                Capsule().fill(Color.white.opacity(0.16)).frame(height: 4)
                    .overlay(alignment: .leading) {
                        Capsule().fill(Color(red: 0.48, green: 0.82, blue: 1)).frame(width: size.width * 0.36, height: 4.0)
                    }
            }
            .padding(.top, 28)

            HStack {
                Text("1:24"); Spacer(); Text("−2:18")
            }
            .font(.system(size: 12, weight: .medium, design: .rounded))
            .foregroundColor(.white.opacity(0.55))
            .padding(.top, 8)

            HStack(spacing: 34) {
                Image(systemName: "backward.fill")
                Image(systemName: "pause.fill").font(.system(size: 27))
                Image(systemName: "forward.fill")
            }
            .font(.system(size: 21, weight: .semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.top, 28)

            HStack(spacing: 12) {
                metric("SOURCE", appLabel, tint: Color(red: 0.48, green: 0.82, blue: 1))
                metric("STATUS", "Playing", tint: Color(red: 1, green: 0.62, blue: 0.18))
            }
            .padding(.top, 34)

            Spacer(minLength: 20)

            Button(action: morphToIsland) {
                HStack(spacing: 9) {
                    Image(systemName: "arrow.up")
                    Text("Morph into island")
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.black)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(Color.white, in: Capsule())
            }
            .padding(.bottom, max(30.0, size.height * 0.06))
        }
        .padding(.horizontal, 24)
        .frame(width: size.width, height: size.height, alignment: .topLeading)
    }

    private func metric(_ title: String, _ value: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 10, weight: .bold)).foregroundColor(.white.opacity(0.45))
            Text(value).font(.system(size: 15, weight: .semibold)).foregroundColor(tint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func islandPill(width: CGFloat, height: CGFloat) -> some View {
        Button(action: expandToApp) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Color(red: 0.54, green: 0.34, blue: 0.76))
                    .frame(width: 25, height: 25)
                    .overlay(Image(systemName: "waveform").font(.system(size: 12, weight: .semibold)).foregroundColor(.white))
                VStack(alignment: .leading, spacing: 3) {
                    Text(trackTitle)
                        .font(.system(size: 10, weight: .semibold))
                        .lineLimit(1)
                    Capsule().fill(Color.white.opacity(0.7)).frame(width: 44, height: 2)
                }
                Spacer(minLength: 2)
                Image(systemName: "pause.fill").font(.system(size: 10, weight: .bold))
                Circle().fill(Color(red: 1, green: 0.62, blue: 0.18)).frame(width: 7, height: 7)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 11)
            .frame(width: width, height: height)
            .background(Color.black)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open the activity dashboard")
    }

    private func morphToIsland() {
        withAnimation(reduceMotion ? .easeOut(duration: 0.16) : .interpolatingSpring(stiffness: 205, damping: 23.77)) {
            isCompact = true
        }
    }

    private func expandToApp() {
        withAnimation(reduceMotion ? .easeOut(duration: 0.16) : .interpolatingSpring(stiffness: 205, damping: 23.77)) {
            isCompact = false
        }
    }
}
