import SwiftUI
import Combine
import AVFoundation
import Accelerate

/// Spectrum levels for the Now Playing visualizer (0...1 per band, low to high).
@MainActor
final class VisualizerData: ObservableObject {
    nonisolated static let bandCount = 24
    @Published var levels: [Float] = Array(repeating: 0, count: VisualizerData.bandCount)
}

/// Measures the music's spectrum from a tap on the EQ's output (only while the visualizer is
/// shown). The tap runs on the audio thread; levels are sent to the main thread ~30 times a second.
nonisolated final class VisualizerTap: @unchecked Sendable {
    private static let size = 1024
    private let lock = NSLock()
    private var installed = false
    private var setup: FFTSetup?
    private var window = [Float](repeating: 0, count: VisualizerTap.size)
    private var smoothed = [Float](repeating: 0, count: VisualizerData.bandCount)
    private var lastSent: TimeInterval = 0

    init() {
        vDSP_hann_window(&window, vDSP_Length(Self.size), Int32(vDSP_HANN_NORM))
        setup = vDSP_create_fftsetup(vDSP_Length(10), FFTRadix(kFFTRadix2))
    }

    deinit {
        if let setup { vDSP_destroy_fftsetup(setup) }
    }

    func install(on node: AVAudioNode, data: VisualizerData) {
        lock.lock()
        defer { lock.unlock() }
        guard !installed else { return }
        installed = true
        node.installTap(onBus: 0, bufferSize: AVAudioFrameCount(Self.size), format: nil,
                        block: Self.tapBlock(for: self, data: data))
    }

    func remove(from node: AVAudioNode) {
        lock.lock()
        defer { lock.unlock() }
        guard installed else { return }
        installed = false
        node.removeTap(onBus: 0)
    }

    // Built outside the main actor: the engine calls it on its audio thread.
    private static func tapBlock(for tap: VisualizerTap, data: VisualizerData) -> AVAudioNodeTapBlock {
        { buffer, _ in
            guard let levels = tap.measure(buffer) else { return }
            Task { @MainActor in data.levels = levels }
        }
    }

    /// Band levels for one buffer, or nil if it's too soon to send another update.
    private func measure(_ buffer: AVAudioPCMBuffer) -> [Float]? {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastSent >= 1.0 / 30, let setup, let channels = buffer.floatChannelData else { return nil }
        lastSent = now
        let size = Self.size
        let count = min(Int(buffer.frameLength), size)
        guard count > 0 else { return nil }

        // Mono mix of the first two channels, windowed.
        var samples = [Float](repeating: 0, count: size)
        if buffer.format.channelCount > 1 {
            vDSP_vadd(channels[0], 1, channels[1], 1, &samples, 1, vDSP_Length(count))
        } else {
            samples.withUnsafeMutableBufferPointer { $0.baseAddress!.update(from: channels[0], count: count) }
        }
        let windowed = vDSP.multiply(samples, window)

        var real = [Float](repeating: 0, count: size / 2)
        var imag = [Float](repeating: 0, count: size / 2)
        var magnitudes = [Float](repeating: 0, count: size / 2)
        real.withUnsafeMutableBufferPointer { realPointer in
            imag.withUnsafeMutableBufferPointer { imagPointer in
                var split = DSPSplitComplex(realp: realPointer.baseAddress!, imagp: imagPointer.baseAddress!)
                windowed.withUnsafeBufferPointer { input in
                    input.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: size / 2) {
                        vDSP_ctoz($0, 2, &split, 1, vDSP_Length(size / 2))
                    }
                }
                vDSP_fft_zrip(setup, &split, 1, vDSP_Length(10), FFTDirection(FFT_FORWARD))
                vDSP_zvabs(&split, 1, &magnitudes, 1, vDSP_Length(size / 2))
            }
        }

        // Logarithmically spaced bands from ~40 Hz to ~16 kHz, in dB, mapped to 0...1.
        let bands = VisualizerData.bandCount
        let nyquist = Float(buffer.format.sampleRate / 2)
        let binWidth = nyquist / Float(size / 2)
        var result = [Float](repeating: 0, count: bands)
        for band in 0..<bands {
            let low = 40 * powf(400, Float(band) / Float(bands))
            let high = 40 * powf(400, Float(band + 1) / Float(bands))
            let first = max(1, Int(low / binWidth))
            let last = min(size / 2 - 1, max(first, Int(high / binWidth)))
            var peak: Float = 0
            for bin in first...last { peak = max(peak, magnitudes[bin]) }
            let db = 20 * log10f(max(peak / Float(size), 1e-6))
            let level = min(max((db + 70) / 60, 0), 1)
            // Rise quickly, fall slowly.
            smoothed[band] = level > smoothed[band] ? level : smoothed[band] * 0.85 + level * 0.15
            result[band] = smoothed[band]
        }
        return result
    }
}

/// The bars themselves.
struct VisualizerView: View {
    @ObservedObject var data: VisualizerData

    var body: some View {
        GeometryReader { geometry in
            let count = data.levels.count
            let spacing: CGFloat = 4
            let width = (geometry.size.width - spacing * CGFloat(count - 1)) / CGFloat(count)
            HStack(alignment: .bottom, spacing: spacing) {
                ForEach(0..<count, id: \.self) { band in
                    RoundedRectangle(cornerRadius: width / 2)
                        .fill(Color.accentColor.gradient)
                        .frame(width: max(width, 2),
                               height: max(width, geometry.size.height * CGFloat(data.levels[band])))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .animation(.linear(duration: 0.08), value: data.levels)
        }
    }
}
