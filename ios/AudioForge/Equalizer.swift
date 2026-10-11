import Foundation

/// The desktop's 10-band graphic EQ (same bands, range and presets as the Android app).
nonisolated enum EqualizerConfig {
    static let bandCount = 10
    static let frequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    /// Q = 1.0 is about 1.39 octaves wide.
    static let bandwidthOctaves: Float = 1.39
    static let minGainDb: Float = -15
    static let maxGainDb: Float = 15
    static let postGainRange: ClosedRange<Float> = -12...12

    static let presets: [(name: String, gains: [Float])] = [
        ("Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ("Rock", [5, 4, 3, 0, -2, -1, 0, 2, 3, 4]),
        ("Pop", [-1, 1, 3, 4, 3, 0, -1, -1, -1, -1]),
        ("Jazz", [3, 2, 1, 2, -1, -1, 0, 1, 2, 3]),
        ("Classical", [4, 3, 2, 1, -1, -2, -2, 0, 2, 3]),
        ("Dance", [6, 5, 2, 0, 0, -3, -3, 0, 3, 4]),
        ("Hip-Hop", [6, 5, 3, 1, -1, -1, 0, 1, 2, 3]),
        ("Blues", [3, 2, 0, -1, 0, 1, 2, 2, 1, 1]),
        ("Vocal Boost", [-2, -2, -1, 1, 3, 4, 3, 1, 0, -1]),
    ]

    static func label(for frequency: Float) -> String {
        frequency >= 1000 ? "\(Int(frequency / 1000))k" : "\(Int(frequency))"
    }
}

/// linear gain -> dB (0 -> -96 dB, the lowest AVAudioUnitEQ accepts).
nonisolated func linearToDb(_ linear: Float) -> Float {
    linear <= 0.000_016 ? -96 : 20 * log10(linear)
}

/// A ReplayGain tag value ("-6.54 dB", "+1.2", "−3 dB") in dB, or nil if it doesn't parse.
nonisolated func parseReplayGainDb(_ value: String?) -> Float? {
    guard let value else { return nil }
    let cleaned = value
        .replacingOccurrences(of: "db", with: "", options: .caseInsensitive)
        .replacingOccurrences(of: "\u{2212}", with: "-")     // Unicode minus sign
        .replacingOccurrences(of: "+", with: "")
        .trimmingCharacters(in: .whitespaces)
    guard let db = Float(cleaned), db.isFinite else { return nil }
    return db
}
