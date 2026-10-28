import SwiftUI

/// Shared spring numbers for SwiftUI and the Android Skip port.
///
/// Skip maps `interpolatingSpring(stiffness:damping:)` onto Compose springs.
/// The Kotlin `Spring` stepper uses the equivalent stiffness + dampingRatio
/// (`dampingRatio = damping / (2 * sqrt(stiffness))`).
public enum IslandMotion {
    public static let dropStiffness: Double = 170
    public static let dropDamping: Double = 16.69
    public static var dropAnimation: Animation {
        .interpolatingSpring(stiffness: dropStiffness, damping: dropDamping)
    }

    public static let shapeStiffness: Double = 205
    public static let widthDamping: Double = 23.77
    public static let heightDamping: Double = 25.2
    public static let centerDamping: Double = 25.77
    public static let radiusDamping: Double = 26.63
    public static let contentResponse: Double = 0.38
    public static let contentDampingFraction: Double = 0.92
    public static let pressResponse: Double = 0.18
    public static let pressDampingFraction: Double = 0.76

    public static let idleSideMargin: CGFloat = 2
    public static let idleHeight: CGFloat = 14
    public static let compactHeight: CGFloat = 36
    public static let splitWidth: CGFloat = 180
    public static let compactWidth: CGFloat = 232

    public static var shapeAnimation: Animation {
        .interpolatingSpring(stiffness: shapeStiffness, damping: widthDamping)
    }

    public static var contentAnimation: Animation {
        .spring(response: contentResponse, dampingFraction: contentDampingFraction)
    }
}
