import Foundation
import Combine

/// What the app learns about a song while you use it.
nonisolated struct SongStats: Codable, Equatable, Sendable {
    var playCount = 0
    var lastPlayed: Date?
    var favorite = false
    /// Long tracks (audiobooks, mixes): where you stopped, in seconds.
    var resumePosition: TimeInterval?
    /// Lyrics timing adjustment in seconds (+ shows lines later).
    var lyricsOffset: Double?
}

/// What "Analyze Your Songs' Key and BPM" found in a song's audio.
nonisolated struct SongAnalysis: Codable, Equatable, Sendable {
    static let currentVersion = 1

    var version = SongAnalysis.currentVersion
    /// Length of the audio that was analyzed; if the file's audio changes, the analysis is redone.
    var duration: TimeInterval
    /// nil when the song has no steady beat.
    var bpm: Double?
    /// 0...1: how sure the beat detection is (beat-matching needs it to be high).
    var bpmConfidence: Double
    /// Time of the first beat, in seconds (the beat grid is firstBeat + n × 60/bpm).
    var firstBeat: TimeInterval
    /// 0-11 = C...B major, 12-23 = C...B minor; nil when unclear.
    var keyIndex: Int?
    var keyConfidence: Double
    /// First and last moments that aren't silence.
    var audioStart: TimeInterval
    var audioEnd: TimeInterval
    /// Where the ending gets quiet (a fade-out), ≤ audioEnd.
    var outroStart: TimeInterval

    var key: MusicKey? { keyIndex.map(MusicKey.init(index:)) }
    var beatPeriod: TimeInterval? { bpm.map { 60 / $0 } }
    /// Steady enough to line beats up with another song.
    var isBeatReliable: Bool { bpm != nil && bpmConfidence >= 0.5 }
}

/// A musical key, with its Camelot-wheel code (8A, 8B…) used for harmonic mixing.
nonisolated struct MusicKey: Hashable, Sendable {
    /// 0 = C … 11 = B.
    let pitchClass: Int
    let isMinor: Bool

    init(index: Int) {
        pitchClass = ((index % 12) + 12) % 12
        isMinor = index >= 12
    }

    init(pitchClass: Int, isMinor: Bool) {
        self.pitchClass = ((pitchClass % 12) + 12) % 12
        self.isMinor = isMinor
    }

    var index: Int { pitchClass + (isMinor ? 12 : 0) }

    private static let displayNames = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
    private static let tagNames = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

    /// "A minor", "E♭ major".
    var name: String { "\(Self.displayNames[pitchClass]) \(isMinor ? "minor" : "major")" }
    /// "Am", "E♭".
    var shortName: String { Self.displayNames[pitchClass] + (isMinor ? "m" : "") }
    /// How the key is written into tags (ID3 TKEY / INITIALKEY): "Am", "D#".
    var tagValue: String { Self.tagNames[pitchClass] + (isMinor ? "m" : "") }

    /// 1-12 on the Camelot wheel (C major = 8B, A minor = 8A).
    var camelotNumber: Int {
        let majorPitch = isMinor ? (pitchClass + 3) % 12 : pitchClass   // relative major
        return (majorPitch * 7 % 12 + 7) % 12 + 1
    }

    /// "8A" (minor) / "8B" (major).
    var camelot: String { "\(camelotNumber)\(isMinor ? "A" : "B")" }

    /// Keys that mix well: the same key, its relative major/minor, or one step around the wheel.
    func isCompatible(with other: MusicKey) -> Bool {
        if camelotNumber == other.camelotNumber { return true }
        guard isMinor == other.isMinor else { return false }
        let difference = abs(camelotNumber - other.camelotNumber)
        return difference == 1 || difference == 11
    }

    /// Reads a key tag: "Am", "C#m", "Ebmaj", "8A", "F minor"…
    static func parse(_ text: String) -> MusicKey? {
        let value = text.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        // Camelot code.
        if let letter = value.last, letter == "A" || letter == "B" || letter == "a" || letter == "b",
           let number = Int(value.dropLast()), (1...12).contains(number) {
            let minor = letter == "A" || letter == "a"
            for pitch in 0..<12 {
                let key = MusicKey(pitchClass: pitch, isMinor: minor)
                if key.camelotNumber == number { return key }
            }
        }
        let names = ["C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11]
        let chars = Array(value)
        guard let base = names[String(chars[0]).uppercased()] else { return nil }
        var pitch = base
        var rest = String(chars.dropFirst())
        if rest.hasPrefix("#") || rest.hasPrefix("♯") { pitch += 1; rest.removeFirst() }
        else if rest.hasPrefix("b") || rest.hasPrefix("♭") { pitch -= 1; rest.removeFirst() }
        let lower = rest.trimmingCharacters(in: .whitespaces).lowercased()
        let minor = lower.hasPrefix("m") && !lower.hasPrefix("maj") || lower.hasPrefix("min")
        return MusicKey(pitchClass: pitch, isMinor: minor)
    }
}

/// Play counts, favourites, resume positions, lyrics offsets and audio analysis, per song key.
/// Saved to Application Support (songstats.json, analysis.json) shortly after each change.
@MainActor
final class SongDataStore: ObservableObject {
    @Published private(set) var stats: [String: SongStats] = [:]
    @Published private(set) var analysis: [String: SongAnalysis] = [:]

    private let statsURL: URL
    private let analysisURL: URL
    private var statsSave: Task<Void, Never>?
    private var analysisSave: Task<Void, Never>?

    init() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        statsURL = folder.appendingPathComponent("songstats.json")
        analysisURL = folder.appendingPathComponent("analysis.json")
        if let data = try? Data(contentsOf: statsURL),
           let decoded = try? JSONDecoder().decode([String: SongStats].self, from: data) {
            stats = decoded
        }
        if let data = try? Data(contentsOf: analysisURL),
           let decoded = try? JSONDecoder().decode([String: SongAnalysis].self, from: data) {
            analysis = decoded.filter { $0.value.version == SongAnalysis.currentVersion }
        }
    }

    // MARK: Stats

    func stats(for key: String) -> SongStats { stats[key] ?? SongStats() }

    func isFavorite(_ key: String) -> Bool { stats[key]?.favorite ?? false }

    func toggleFavorite(_ key: String) {
        updateStats(key) { $0.favorite.toggle() }
    }

    func setFavorite(_ keys: [String], _ favorite: Bool) {
        for key in keys { updateStats(key) { $0.favorite = favorite } }
    }

    func recordPlay(_ key: String) {
        updateStats(key) {
            $0.playCount += 1
            $0.lastPlayed = Date()
        }
    }

    func setResumePosition(_ key: String, _ position: TimeInterval?) {
        guard stats[key]?.resumePosition != position else { return }
        updateStats(key) { $0.resumePosition = position }
    }

    func setLyricsOffset(_ key: String, _ offset: Double) {
        updateStats(key) { $0.lyricsOffset = abs(offset) < 0.001 ? nil : offset }
    }

    private func updateStats(_ key: String, _ change: (inout SongStats) -> Void) {
        var value = stats[key] ?? SongStats()
        change(&value)
        stats[key] = value
        statsSave?.cancel()
        statsSave = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            Self.write(self.stats, to: self.statsURL)
        }
    }

    // MARK: Analysis

    /// The analysis of `song`, if there is one for its current audio.
    func analysis(for song: Song) -> SongAnalysis? {
        guard let result = analysis[song.key] else { return nil }
        // Tag edits don't change the audio's length; replacing the file usually does.
        if song.duration > 0 && abs(result.duration - song.duration) > 1 { return nil }
        return result
    }

    func store(_ result: SongAnalysis, for key: String) {
        analysis[key] = result
        analysisSave?.cancel()
        analysisSave = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self else { return }
            Self.write(self.analysis, to: self.analysisURL)
        }
    }

    func analyzedCount(of songs: [Song]) -> Int {
        songs.reduce(0) { $0 + (analysis(for: $1) != nil ? 1 : 0) }
    }

    /// Writes everything now (the app is going to the background).
    func saveNow() {
        if statsSave != nil { Self.write(stats, to: statsURL) }
        if analysisSave != nil { Self.write(analysis, to: analysisURL) }
        statsSave?.cancel()
        analysisSave?.cancel()
        statsSave = nil
        analysisSave = nil
    }

    private static func write<T: Encodable>(_ value: T, to url: URL) {
        do {
            try JSONEncoder().encode(value).write(to: url, options: .atomic)
        } catch {
            print("Could not save \(url.lastPathComponent): \(error)")
        }
    }
}

/// Runs the key/BPM analysis over the library, two songs at a time, in the background.
@MainActor
final class LibraryAnalyzer: ObservableObject {
    @Published private(set) var progress: (done: Int, total: Int)?

    private let store: SongDataStore
    private let settings: SettingsStore
    private var task: Task<Void, Never>?

    init(store: SongDataStore, settings: SettingsStore) {
        self.store = store
        self.settings = settings
    }

    var isRunning: Bool { task != nil }

    /// Analyzes every song that hasn't been analyzed yet. When all are done, Smart Options unlock.
    func start(_ songs: [Song]) {
        guard task == nil else { return }
        let todo = songs.filter { store.analysis(for: $0) == nil }
        guard !todo.isEmpty else {
            settings.update { $0.analysisCompleted = true }
            return
        }
        progress = (0, todo.count)
        task = Task { [weak self] in
            await self?.run(todo)
        }
    }

    func cancel() {
        task?.cancel()
    }

    private func run(_ todo: [Song]) async {
        var done = 0
        await withTaskGroup(of: (String, SongAnalysis?).self) { group in
            var pending = todo.makeIterator()
            for _ in 0..<2 {
                if let song = pending.next() {
                    group.addTask { (song.key, AudioAnalyzer.analyze(song.url)) }
                }
            }
            while let finished = await group.next() {
                let (key, result) = finished
                if let result { store.store(result, for: key) }
                done += 1
                progress = (done, todo.count)
                if Task.isCancelled {
                    group.cancelAll()
                    continue
                }
                if let song = pending.next() {
                    group.addTask { (song.key, AudioAnalyzer.analyze(song.url)) }
                }
            }
        }
        if !Task.isCancelled { settings.update { $0.analysisCompleted = true } }
        store.saveNow()
        progress = nil
        task = nil
    }
}
