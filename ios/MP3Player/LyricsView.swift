import SwiftUI

nonisolated struct LyricLine: Identifiable, Hashable, Sendable {
    let id: Int
    let time: TimeInterval
    let text: String
    /// Word-by-word timing ("enhanced" .lrc with <mm:ss.xx> before each word), if the file has it.
    var words: [LyricWord] = []
}

nonisolated struct LyricWord: Hashable, Sendable {
    let time: TimeInterval
    let text: String
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
        var entries: [(time: TimeInterval, text: String, words: [LyricWord])] = []

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

            let content = String(line)
            let plain = content
                .replacingOccurrences(of: "<\\d+:\\d+(\\.\\d+)?>", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            let words = parseWords(content)
            for time in times {
                entries.append((time, plain, words))
            }
        }

        return entries
            .sorted { $0.time < $1.time }
            .enumerated()
            .map { index, entry in
                LyricLine(id: index, time: max(entry.time - offset, 0), text: entry.text,
                          words: entry.words.map { LyricWord(time: max($0.time - offset, 0), text: $0.text) })
            }
    }

    /// "<00:12.00>Hello <00:12.50>world" → [(12.0, "Hello "), (12.5, "world")].
    private static func parseWords(_ content: String) -> [LyricWord] {
        guard content.contains("<"),
              let regex = try? NSRegularExpression(pattern: "<(\\d+:\\d+(?:[.:]\\d+)?)>") else { return [] }
        let text = content as NSString
        let matches = regex.matches(in: content, range: NSRange(location: 0, length: text.length))
        guard !matches.isEmpty else { return [] }
        var words: [LyricWord] = []
        for (index, match) in matches.enumerated() {
            let tag = text.substring(with: match.range(at: 1))
            guard let time = parseTime(Substring(tag)) else { continue }
            let start = match.range.location + match.range.length
            let end = index + 1 < matches.count ? matches[index + 1].range.location : text.length
            let word = text.substring(with: NSRange(location: start, length: max(0, end - start)))
            if !word.isEmpty { words.append(LyricWord(time: time, text: word)) }
        }
        return words
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
    /// Per-song timing adjustment (+ shows lines later).
    var offset: Double = 0
    /// Bigger text for the full-screen lyrics.
    var large = false
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var clock: PlaybackClock

    private var now: TimeInterval { clock.time + 0.15 - offset }

    /// The last line that has started (binary search; lines are sorted by time).
    private var currentID: Int? {
        let now = self.now
        var low = 0, high = lines.count - 1, found: Int?
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].time <= now { found = mid; low = mid + 1 } else { high = mid - 1 }
        }
        return found.map { lines[$0].id }
    }

    var body: some View {
        let current = currentID

        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: large ? 28 : 20) {
                    ForEach(lines) { line in
                        lineText(line, isCurrent: line.id == current)
                            .font(large ? .largeTitle.bold() : .title2.bold())
                            .opacity(line.id == current ? 1 : 0.55)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { player.seek(to: max(line.time + offset, 0)) }
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

    /// The current line of word-timed lyrics lights up word by word.
    private func lineText(_ line: LyricLine, isCurrent: Bool) -> Text {
        guard isCurrent, !line.words.isEmpty else {
            return Text(line.text.isEmpty ? "♪" : line.text)
                .foregroundColor(isCurrent ? .primary : .secondary)
        }
        let now = self.now
        return line.words.reduce(Text("")) { result, word in
            result + Text(word.text).foregroundColor(word.time <= now ? .primary : .secondary)
        }
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

/// Lyrics filling the screen, with a timing adjustment for files that are slightly off.
struct FullScreenLyricsView: View {
    let song: Song
    let content: LyricsContent
    @EnvironmentObject private var songData: SongDataStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let offset = songData.stats(for: song.key).lyricsOffset ?? 0

        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).font(.headline).lineLimit(1)
                    Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title)
                        .symbolRenderingMode(.hierarchical)
                }
                .accessibilityLabel("Close")
            }
            .padding()

            Group {
                switch content {
                case .synced(let lines): LyricsView(lines: lines, offset: offset, large: true)
                case .plain(let lines): PlainLyricsView(lines: lines)
                default: Spacer()
                }
            }
            .padding(.horizontal)

            if case .synced = content {
                HStack(spacing: 16) {
                    Button { songData.setLyricsOffset(song.key, offset - 0.5) } label: {
                        Label("Earlier", systemImage: "minus.circle")
                    }
                    Text(offset == 0 ? "In sync" : String(format: "%+.1f s", offset))
                        .font(.subheadline.monospacedDigit())
                        .frame(minWidth: 70)
                    Button { songData.setLyricsOffset(song.key, offset + 0.5) } label: {
                        Label("Later", systemImage: "plus.circle")
                    }
                    if offset != 0 {
                        Button("Reset") { songData.setLyricsOffset(song.key, 0) }
                    }
                }
                .labelStyle(.iconOnly)
                .font(.title2)
                .padding()
                .accessibilityElement(children: .contain)
            }
        }
        .background(.background)
    }
}
