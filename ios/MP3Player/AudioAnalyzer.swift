import Foundation
import AVFoundation
import Accelerate

/// Finds a song's tempo (BPM and beat grid), key, silence at the start/end and where its
/// ending fades out — on the phone, with Accelerate, no network.
///
/// - Tempo: spectral-flux onset envelope → autocorrelation (with a preference around
///   120 BPM) → refined by a comb search that also gives the beat phase.
/// - Key: chromagram (energy per note C…B) correlated with the Krumhansl major/minor
///   key profiles in all 24 keys.
/// - Silence / outro: loudness envelope against the song's typical loudness.
nonisolated enum AudioAnalyzer {
    /// Audio is analyzed in mono at this rate (enough for beats and notes up to ~5 kHz).
    static let sampleRate: Double = 11_025
    /// Long files (mixes, audiobooks) are only analyzed for their first 10 minutes.
    static let maxAnalyzedSeconds: Double = 600

    static func analyze(_ url: URL) -> SongAnalysis? {
        guard let decoded = decodeMono(url) else { return nil }
        let samples = decoded.samples
        guard samples.count > Int(sampleRate * 5) else { return nil }   // under 5 s: nothing useful

        let loudness = loudnessAnalysis(samples, fullDuration: decoded.duration,
                                        analyzedWholeFile: decoded.isComplete)
        let tempo = tempoAnalysis(samples)
        let key = keyAnalysis(samples)

        return SongAnalysis(
            duration: decoded.duration,
            bpm: tempo.bpm,
            bpmConfidence: tempo.confidence,
            firstBeat: tempo.firstBeat,
            keyIndex: key.index,
            keyConfidence: key.confidence,
            audioStart: loudness.start,
            audioEnd: loudness.end,
            outroStart: loudness.outroStart)
    }

    // MARK: - Decoding

    /// Holds what the converter's input block needs (it can't capture mutable locals).
    nonisolated private final class FileReader: @unchecked Sendable {
        let file: AVAudioFile
        let buffer: AVAudioPCMBuffer
        var finished = false

        init?(file: AVAudioFile) {
            guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 32_768)
            else { return nil }
            self.file = file
            self.buffer = buffer
        }
    }

    private static func decodeMono(_ url: URL) -> (samples: [Float], duration: Double, isComplete: Bool)? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let inFormat = file.processingFormat
        guard inFormat.sampleRate > 0,
              let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                            channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inFormat, to: outFormat),
              let reader = FileReader(file: file),
              let output = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: 16_384) else { return nil }
        converter.downmix = true

        let duration = Double(file.length) / inFormat.sampleRate
        let limit = Int(min(duration, maxAnalyzedSeconds) * sampleRate)
        var samples: [Float] = []
        samples.reserveCapacity(limit + 16_384)

        while samples.count < limit {
            output.frameLength = 0
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { packetCount, inputStatus in
                if reader.finished {
                    inputStatus.pointee = .endOfStream
                    return nil
                }
                let frames = min(packetCount, reader.buffer.frameCapacity)
                do {
                    try reader.file.read(into: reader.buffer, frameCount: frames)
                } catch {
                    reader.finished = true
                    inputStatus.pointee = .endOfStream
                    return nil
                }
                if reader.buffer.frameLength == 0 {
                    reader.finished = true
                    inputStatus.pointee = .endOfStream
                    return nil
                }
                inputStatus.pointee = .haveData
                return reader.buffer
            }
            if let channel = output.floatChannelData?[0], output.frameLength > 0 {
                samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
            }
            if status == .endOfStream || status == .error || output.frameLength == 0 { break }
        }
        if samples.count > limit { samples.removeLast(samples.count - limit) }
        return (samples, duration, duration <= maxAnalyzedSeconds)
    }

    // MARK: - Spectra

    /// Calls `body` with the magnitude spectrum (size/2 bins) of each Hann-windowed frame.
    private static func forEachSpectrum(_ samples: [Float], size: Int, hop: Int,
                                        _ body: (Int, UnsafeMutableBufferPointer<Float>) -> Void) {
        let log2n = vDSP_Length(log2(Double(size)))
        guard samples.count >= size, let setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else { return }
        defer { vDSP_destroy_fftsetup(setup) }

        var window = [Float](repeating: 0, count: size)
        vDSP_hann_window(&window, vDSP_Length(size), Int32(vDSP_HANN_NORM))
        var frame = [Float](repeating: 0, count: size)
        var real = [Float](repeating: 0, count: size / 2)
        var imag = [Float](repeating: 0, count: size / 2)
        var magnitudes = [Float](repeating: 0, count: size / 2)

        var index = 0
        var start = 0
        samples.withUnsafeBufferPointer { input in
            while start + size <= samples.count {
                vDSP_vmul(input.baseAddress! + start, 1, window, 1, &frame, 1, vDSP_Length(size))
                real.withUnsafeMutableBufferPointer { realPointer in
                    imag.withUnsafeMutableBufferPointer { imagPointer in
                        var split = DSPSplitComplex(realp: realPointer.baseAddress!, imagp: imagPointer.baseAddress!)
                        frame.withUnsafeBufferPointer { framePointer in
                            framePointer.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: size / 2) {
                                vDSP_ctoz($0, 2, &split, 1, vDSP_Length(size / 2))
                            }
                        }
                        vDSP_fft_zrip(setup, &split, 1, log2n, FFTDirection(FFT_FORWARD))
                        vDSP_zvabs(&split, 1, &magnitudes, 1, vDSP_Length(size / 2))
                    }
                }
                magnitudes.withUnsafeMutableBufferPointer { body(index, $0) }
                index += 1
                start += hop
            }
        }
    }

    // MARK: - Tempo

    private static let onsetFrameSize = 1024
    private static let onsetHop = 128
    private static var onsetHopTime: Double { Double(onsetHop) / sampleRate }

    private static func tempoAnalysis(_ samples: [Float]) -> (bpm: Double?, confidence: Double, firstBeat: Double) {
        // 1. Onset strength: how much the (log-compressed) spectrum grows from one frame to the next.
        let bins = onsetFrameSize / 2
        var previous = [Float](repeating: 0, count: bins)
        var current = [Float](repeating: 0, count: bins)
        var envelope: [Float] = []
        envelope.reserveCapacity(samples.count / onsetHop + 1)
        forEachSpectrum(samples, size: onsetFrameSize, hop: onsetHop) { index, magnitudes in
            var flux: Float = 0
            for bin in 0..<bins {
                let value = log1pf(100 * magnitudes[bin])
                current[bin] = value
                if index > 0 { flux += max(0, value - previous[bin]) }
            }
            swap(&previous, &current)
            envelope.append(flux)
        }
        guard envelope.count > 400 else { return (nil, 0, 0) }

        // 2. Keep only the peaks: subtract a ~0.5 s moving average, clip negatives, normalize.
        let window = Int(0.5 / onsetHopTime)
        var smoothed = [Float](repeating: 0, count: envelope.count)
        var sum: Float = 0
        for i in 0..<envelope.count {
            sum += envelope[i]
            if i >= window { sum -= envelope[i - window] }
            smoothed[i] = max(0, envelope[i] - sum / Float(min(i + 1, window)))
        }
        var mean: Float = 0
        vDSP_meanv(smoothed, 1, &mean, vDSP_Length(smoothed.count))
        guard mean > 0 else { return (nil, 0, 0) }
        smoothed = vDSP.multiply(1 / mean, smoothed)

        // 3. Autocorrelation over 60-200 BPM, weighted towards ~120 BPM (one octave either side).
        let minLag = Int((60.0 / 200) / onsetHopTime)
        let maxLag = Int((60.0 / 60) / onsetHopTime)
        guard smoothed.count > maxLag * 4 else { return (nil, 0, 0) }
        var correlation = [Double](repeating: 0, count: maxLag + 2)
        smoothed.withUnsafeBufferPointer { e in
            for lag in max(1, minLag - 1)...(maxLag + 1) {
                var dot: Float = 0
                let count = e.count - lag
                vDSP_dotpr(e.baseAddress!, 1, e.baseAddress! + lag, 1, &dot, vDSP_Length(count))
                correlation[lag] = Double(dot) / Double(count)
            }
        }
        var bestLag = minLag
        var bestScore = -Double.infinity
        var scoreSum = 0.0
        for lag in minLag...maxLag {
            let bpm = 60 / (Double(lag) * onsetHopTime)
            let octaves = log2(bpm / 120)
            let score = correlation[lag] * exp(-0.5 * octaves * octaves)
            scoreSum += correlation[lag]
            if score > bestScore {
                bestScore = score
                bestLag = lag
            }
        }
        let average = scoreSum / Double(maxLag - minLag + 1)
        let peakRatio = average > 0 ? correlation[bestLag] / average : 0
        // Parabolic interpolation around the peak for a sub-frame period.
        let a = correlation[bestLag - 1], b = correlation[bestLag], c = correlation[bestLag + 1]
        let denominator = a - 2 * b + c
        let offset = denominator != 0 ? max(-0.5, min(0.5, 0.5 * (a - c) / denominator)) : 0
        let roughPeriod = Double(bestLag) + offset

        // 4. Comb search: the period (±2%) and phase whose beat positions land on the most onsets.
        var bestPeriod = roughPeriod
        var bestPhase = 0.0
        var bestComb = -Double.infinity
        for step in -40...40 {
            let period = roughPeriod * (1 + Double(step) * 0.0005)
            let phases = Int(period)
            for phase in 0..<max(phases, 1) {
                var position = Double(phase)
                var total = 0.0
                var count = 0
                while position < Double(smoothed.count - 1) {
                    let i = Int(position)
                    let fraction = Float(position - Double(i))
                    total += Double(smoothed[i] * (1 - fraction) + smoothed[i + 1] * fraction)
                    count += 1
                    position += period
                }
                let score = count > 0 ? total / Double(count) : 0
                if score > bestComb {
                    bestComb = score
                    bestPeriod = period
                    bestPhase = Double(phase)
                }
            }
        }

        let bpm = 60 / (bestPeriod * onsetHopTime)
        // How much the beat stands out: ~1 for no pulse, 2+ for a steady drum beat.
        let confidence = max(0, min(1, (peakRatio - 1.15) / 0.85))
        // Onset frame i is centred at (i × hop + frameSize / 2) samples.
        let firstBeat = (bestPhase * Double(onsetHop) + Double(onsetFrameSize / 2)) / sampleRate
        guard confidence > 0.1, bpm.isFinite else { return (nil, confidence, 0) }
        return ((bpm * 10).rounded() / 10, confidence, firstBeat)
    }

    // MARK: - Key

    /// Krumhansl-Kessler key profiles (how much each scale degree is heard in a key).
    private static let majorProfile: [Double] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
    private static let minorProfile: [Double] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]

    private static func keyAnalysis(_ samples: [Float]) -> (index: Int?, confidence: Double) {
        let size = 4096
        let binWidth = sampleRate / Double(size)
        // Which note (pitch class) each FFT bin belongs to, from C2 (65 Hz) to ~C7 (2.1 kHz).
        var pitchOfBin = [Int](repeating: -1, count: size / 2)
        for bin in 1..<(size / 2) {
            let frequency = Double(bin) * binWidth
            guard frequency >= 65, frequency <= 2100 else { continue }
            let midi = 69 + 12 * log2(frequency / 440)
            pitchOfBin[bin] = (Int(midi.rounded()) % 12 + 12) % 12
        }

        var chroma = [Double](repeating: 0, count: 12)
        forEachSpectrum(samples, size: size, hop: size) { _, magnitudes in
            var frame = [Double](repeating: 0, count: 12)
            var energy = 0.0
            for bin in 1..<(size / 2) where pitchOfBin[bin] >= 0 {
                let value = Double(magnitudes[bin])
                frame[pitchOfBin[bin]] += value
                energy += value
            }
            guard energy > 0 else { return }               // silence
            for pitch in 0..<12 { chroma[pitch] += frame[pitch] / energy }
        }
        guard chroma.contains(where: { $0 > 0 }) else { return (nil, 0) }

        var scores: [(index: Int, score: Double)] = []
        for tonic in 0..<12 {
            scores.append((tonic, correlation(chroma, rotated(majorProfile, by: tonic))))
            scores.append((12 + tonic, correlation(chroma, rotated(minorProfile, by: tonic))))
        }
        scores.sort { $0.score > $1.score }
        let best = scores[0]
        let margin = best.score - scores[1].score
        let confidence = max(0, min(1, margin * 6 + max(0, best.score - 0.5)))
        guard best.score > 0.3 else { return (nil, confidence) }
        return (best.index, confidence)
    }

    /// The profile for the key whose tonic is `tonic` (profile[0] is the tonic).
    private static func rotated(_ profile: [Double], by tonic: Int) -> [Double] {
        (0..<12).map { profile[(($0 - tonic) % 12 + 12) % 12] }
    }

    private static func correlation(_ x: [Double], _ y: [Double]) -> Double {
        let n = Double(x.count)
        let meanX = x.reduce(0, +) / n, meanY = y.reduce(0, +) / n
        var covariance = 0.0, varianceX = 0.0, varianceY = 0.0
        for i in 0..<x.count {
            let dx = x[i] - meanX, dy = y[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        let denominator = (varianceX * varianceY).squareRoot()
        return denominator > 0 ? covariance / denominator : 0
    }

    // MARK: - Silence and outro

    private static func loudnessAnalysis(_ samples: [Float], fullDuration: Double, analyzedWholeFile: Bool)
        -> (start: Double, end: Double, outroStart: Double) {
        let hop = 512
        let hopTime = Double(hop) / sampleRate
        let frames = samples.count / hop
        guard frames > 20 else { return (0, fullDuration, fullDuration) }

        var levels = [Float](repeating: -120, count: frames)
        samples.withUnsafeBufferPointer { input in
            for i in 0..<frames {
                var rms: Float = 0
                vDSP_rmsqv(input.baseAddress! + i * hop, 1, &rms, vDSP_Length(hop))
                levels[i] = 20 * log10f(max(rms, 1e-7))
            }
        }
        let peak = levels.max() ?? -120
        let threshold = max(peak - 45, -60)                 // quieter than this counts as silence
        let first = levels.firstIndex { $0 > threshold } ?? 0
        let last = levels.lastIndex { $0 > threshold } ?? (frames - 1)
        let start = Double(first) * hopTime

        // Only the analyzed part is known; for long files the end stays the file's end.
        guard analyzedWholeFile else { return (start, fullDuration, fullDuration) }
        let end = min(Double(last + 1) * hopTime, fullDuration)

        // Typical loudness of the song (median of the non-silent part).
        let body = levels[first...last].sorted()
        let typical = body[body.count / 2]

        // Outro: walking back from the end, the last moment the 1-second average loudness is
        // still within 6 dB of typical. Limited to the last 20 s.
        let averageWindow = max(1, Int(1 / hopTime))
        var outroFrame = last
        var index = last
        let earliest = max(first, last - Int(20 / hopTime))
        while index >= earliest {
            let lower = max(first, index - averageWindow + 1)
            let window = levels[lower...index]
            let average = window.reduce(0, +) / Float(window.count)
            if average >= typical - 6 { break }
            outroFrame = index
            index -= 1
        }
        let outroStart = min(Double(outroFrame) * hopTime, end)
        return (start, end, outroStart)
    }
}
