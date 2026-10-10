import SwiftUI

nonisolated struct LyricLine: Identifiable, Hashable, Sendable {
    let id: Int
    let time: TimeInterval
    let text: String
}

extension Array where Element == LyricLine {
    /// Index of the last line that has started at `time` (binary search; lines are sorted by time).
    /// Slightly early, so a line shows as it starts being sung.
    nonisolated func currentIndex(at time: TimeInterval) -> Int? {
        let now = time + 0.15
        var low = 0, high = count - 1, found: Int?
        while low <= high {
            let mid = (low + high) / 2
            if self[mid].time <= now { found = mid; low = mid + 1 } else { high = mid - 1 }
        }
        return found
    }
}

/// What the Now Playing screen shows in its lyrics panel.
nonisolated enum LyricsContent: Equatable, Sendable {
    case none
    case loading
    case notFound
    case plain([String])
    case synced([LyricLine])

    var isAvailable: Bool {
        switch self {
        case .plain, .synced: true
        default: false
        }
    }

    /// Synced if the text has valid time tags (and may be synced), else plain lines.
    static func from(_ text: String, maybeSynced: Bool) -> LyricsContent {
        if maybeSynced {
            let lines = LRCParser.parse(text)
            if !lines.isEmpty { return .synced(lines) }
        }
        let plain = text.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return plain.isEmpty ? .notFound : .plain(plain)
    }
}

/// Reads .lrc files: [mm:ss.xx] lines, multiple timestamps per line, [offset:ms],
/// word-level <mm:ss.xx> tags (stripped). Handles UTF-8, UTF-16 and GBK/GB18030 files.
nonisolated enum LRCParser {
    static func load(from url: URL) -> [LyricLine] {
        guard let text = readText(url) else { return [] }
        return parse(text)
    }

    static func readText(_ url: URL) -> String? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let bytes = [UInt8](data.prefix(2))
        if bytes == [0xFF, 0xFE] || bytes == [0xFE, 0xFF] {
            return String(data: data, encoding: .utf16)
        }
        if let text = String(data: data, encoding: .utf8) {
            return text.replacingOccurrences(of: "\u{FEFF}", with: "")
        }
        let gb18030 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        return String(data: data, encoding: gb18030) ?? String(data: data, encoding: .isoLatin1)
    }

    static func parse(_ text: String) -> [LyricLine] {
        var offset: TimeInterval = 0
        var entries: [(time: TimeInterval, text: String)] = []

        for rawLine in text.components(separatedBy: .newlines) {
            var line = Substring(rawLine.trimmingCharacters(in: .whitespaces))
            var times: [TimeInterval] = []

            // Read every [tag] at the start of the line.
            while line.hasPrefix("["), let close = line.firstIndex(of: "]") {
                let tag = line[line.index(after: line.startIndex)..<close]
                line = line[line.index(after: close)...]

                if let time = parseTime(tag) {
                    times.append(time)
                } else if tag.lowercased().hasPrefix("offset:"),
                          let ms = Double(tag.dropFirst(7).trimmingCharacters(in: .whitespaces)) {
                    offset = ms / 1000
                }
                // Other tags ([ar:], [ti:], [by:]...) are ignored.
            }

            let words = String(line)
                .replacingOccurrences(of: "<\\d+:\\d+(\\.\\d+)?>", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            for time in times {
                entries.append((time, words))
            }
        }

        return entries
            .map { (max($0.time - offset, 0), $0.text) }
            .sorted { $0.0 < $1.0 }
            .enumerated()
            .map { LyricLine(id: $0.offset, time: $0.element.0, text: $0.element.1) }
    }

    /// "mm:ss", "mm:ss.xx" or "mm:ss:xx" → seconds.
    private static func parseTime(_ tag: Substring) -> TimeInterval? {
        let parts = tag.split(separator: ":")
        guard parts.count == 2 || parts.count == 3,
              let minutes = Double(parts[0]),
              let seconds = Double(parts[1]) else { return nil }
        var time = minutes * 60 + seconds
        if parts.count == 3, let hundredths = Double(parts[2]) {
            time += hundredths / 100
        }
        return time
    }
}

/// Scrolling, auto-following lyrics. Tap a line to jump to it.
struct LyricsView: View {
    let lines: [LyricLine]
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var clock: PlaybackClock

    private var currentID: Int? {
        lines.currentIndex(at: clock.time).map { lines[$0].id }
    }

    var body: some View {
        let current = currentID

        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(lines) { line in
                        Text(line.text.isEmpty ? "♪" : line.text)
                            .font(.title2.bold())
                            .foregroundStyle(line.id == current ? Color.primary : Color.secondary)
                            .opacity(line.id == current ? 1 : 0.55)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { player.seek(to: line.time) }
                            .id(line.id)
                    }
                }
                .padding(.vertical, 160)
            }
            .onAppear {
                if let current { proxy.scrollTo(current, anchor: .center) }
            }
            .onChange(of: current) { _, new in
                guard let new else { return }
                withAnimation(.easeInOut(duration: 0.4)) {
                    proxy.scrollTo(new, anchor: .center)
                }
            }
        }
        .mask(fadeMask)
    }

    private var fadeMask: some View {
        LinearGradient(stops: [
            .init(color: .clear, location: 0),
            .init(color: .black, location: 0.12),
            .init(color: .black, location: 0.88),
            .init(color: .clear, location: 1)
        ], startPoint: .top, endPoint: .bottom)
    }
}

/// Unsynced lyrics: just scrollable text.
struct PlainLyricsView: View {
    let lines: [String]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.title3.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.vertical, 40)
        }
    }
}
