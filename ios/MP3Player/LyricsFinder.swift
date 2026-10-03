import Foundation
import CryptoKit

/// Finds lyrics in the desktop/Android order: local cache, then LRCLIB, then
/// Musixmatch (only with an API key), then a same-name .lrc/.txt next to the song.
///
/// LRCLIB results are cached (LRCLIB allows it); Musixmatch's aren't, since
/// its license doesn't grant that.
nonisolated enum LyricsFinder {
    static let musixmatchKeyAccount = "musixmatchApiKey"
    private static let userAgent = "AudioForge-iOS/1.0 ( https://github.com/biasnil/Audio-Forge )"

    static func find(for song: Song) async -> LyricsContent {
        if let cached = readCache(song) { return cached }

        if !song.title.trimmingCharacters(in: .whitespaces).isEmpty {
            if case let (text, synced)? = await fetchFromLRCLIB(song) {
                writeCache(song, text: text, synced: synced)
                return LyricsContent.from(text, maybeSynced: synced)
            }
            if Task.isCancelled { return .none }
            if let text = await fetchFromMusixmatch(song) {
                return LyricsContent.from(text, maybeSynced: false)
            }
        }

        if let url = song.lyricsURL, let text = LRCParser.readText(url) {
            // Tried as synced even for .txt: timed text shows synced, anything else plain.
            return LyricsContent.from(text, maybeSynced: true)
        }
        return .notFound
    }

    // MARK: - LRCLIB (no key needed)

    nonisolated struct Query: Hashable {
        var trackName: String?
        var artistName: String?
        var q: String?
    }

    /// The searches to try, most specific first:
    /// 1. title + artist as tagged;
    /// 2. the title without "(Live)" / "[Remastered]" / "feat. X";
    /// 3. LRCLIB's general search over title + artist;
    /// 4. for an untagged file, "01 - Artist - Title" split into artist and title.
    static func queries(title: String, artist: String, fileName: String) -> [Query] {
        let cleanTitle = title.trimmingCharacters(in: .whitespaces)
        var cleanArtist = artist.trimmingCharacters(in: .whitespaces)
        if cleanArtist == "Unknown Artist" { cleanArtist = "" }
        var result: [Query] = []
        func add(_ query: Query) { if !result.contains(query) { result.append(query) } }

        if !cleanTitle.isEmpty {
            let artistName = cleanArtist.isEmpty ? nil : cleanArtist
            add(Query(trackName: cleanTitle, artistName: artistName))
            let undecorated = cleanTitle
                .replacingOccurrences(of: "\\s*[(\\[（【][^)\\]）】]*[)\\]）】]", with: "", options: .regularExpression)
                .replacingOccurrences(of: "\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", with: "",
                                      options: [.regularExpression, .caseInsensitive])
                .trimmingCharacters(in: .whitespaces)
            if !undecorated.isEmpty && undecorated != cleanTitle {
                add(Query(trackName: undecorated, artistName: artistName))
            }
            let general = [undecorated.isEmpty ? cleanTitle : undecorated, cleanArtist]
                .filter { !$0.isEmpty }.joined(separator: " ")
            add(Query(q: general))
        }

        let baseName = (fileName as NSString).deletingPathExtension.trimmingCharacters(in: .whitespaces)
        if cleanArtist.isEmpty && cleanTitle == baseName {
            let name = cleanTitle.replacingOccurrences(of: "^\\d{1,3}\\s*[-._)]?\\s+", with: "",
                                                       options: .regularExpression)
            let parts = name.components(separatedBy: " - ")
            if parts.count >= 2 {
                let songArtist = parts[0].trimmingCharacters(in: .whitespaces)
                let songTitle = parts.dropFirst().joined(separator: " - ").trimmingCharacters(in: .whitespaces)
                if !songArtist.isEmpty && !songTitle.isEmpty {
                    add(Query(trackName: songTitle, artistName: songArtist))
                }
            } else if !name.isEmpty && name != cleanTitle {
                add(Query(trackName: name))
            }
        }
        return result
    }

    nonisolated struct Candidate {
        var duration: Double?
        var synced: String
        var plain: String
    }

    /// Prefers results within 5 s of the song's length (weeds out live versions
    /// and covers); synced before plain. Returns (text, isSynced).
    static func pick(_ candidates: [Candidate], songDuration: TimeInterval) -> (String, Bool)? {
        let withLyrics = candidates.filter { !$0.synced.isBlank || !$0.plain.isBlank }
        guard !withLyrics.isEmpty else { return nil }
        let matched = songDuration > 0
            ? withLyrics.filter { c in c.duration.map { abs($0 - songDuration) <= 5 } ?? false }
            : []
        for pool in [matched, withLyrics] {
            if let c = pool.first(where: { !$0.synced.isBlank }) { return (c.synced, true) }
            if let c = pool.first(where: { !$0.plain.isBlank }) { return (c.plain, false) }
        }
        return nil
    }

    private static func fetchFromLRCLIB(_ song: Song) async -> (String, Bool)? {
        for query in queries(title: song.title, artist: song.artist, fileName: song.fileName) {
            if Task.isCancelled { return nil }
            var components = URLComponents(string: "https://lrclib.net/api/search")!
            var items: [URLQueryItem] = []
            if let trackName = query.trackName { items.append(URLQueryItem(name: "track_name", value: trackName)) }
            if let artistName = query.artistName { items.append(URLQueryItem(name: "artist_name", value: artistName)) }
            if let q = query.q { items.append(URLQueryItem(name: "q", value: q)) }
            components.queryItems = items
            guard let url = components.url else { continue }
            // Offline or LRCLIB down: the other searches would fail too.
            guard let data = await httpGet(url) else { return nil }
            guard let results = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { continue }
            let candidates = results.map { result in
                Candidate(duration: result["duration"] as? Double,
                          synced: result["syncedLyrics"] as? String ?? "",
                          plain: result["plainLyrics"] as? String ?? "")
            }
            if let picked = pick(candidates, songDuration: song.duration) { return picked }
        }
        return nil
    }

    // MARK: - Musixmatch (needs the user's API key; free keys return a snippet)

    private static func fetchFromMusixmatch(_ song: Song) async -> String? {
        let apiKey = Keychain.read(musixmatchKeyAccount).trimmingCharacters(in: .whitespaces)
        guard !apiKey.isEmpty else { return nil }

        var search = URLComponents(string: "https://api.musixmatch.com/ws/1.1/track.search")!
        var items = [URLQueryItem(name: "q_track", value: song.title)]
        if song.artist != "Unknown Artist" && !song.artist.isEmpty {
            items.append(URLQueryItem(name: "q_artist", value: song.artist))
        }
        items += [URLQueryItem(name: "page_size", value: "1"),
                  URLQueryItem(name: "s_track_rating", value: "desc"),
                  URLQueryItem(name: "apikey", value: apiKey)]
        search.queryItems = items
        guard let searchURL = search.url, let searchData = await httpGet(searchURL),
              let searchJSON = try? JSONSerialization.jsonObject(with: searchData) as? [String: Any],
              let message = searchJSON["message"] as? [String: Any],
              let body = message["body"] as? [String: Any],
              let list = body["track_list"] as? [[String: Any]],
              let track = list.first?["track"] as? [String: Any],
              let trackID = (track["track_id"] as? NSNumber)?.int64Value, trackID > 0 else { return nil }

        var lyrics = URLComponents(string: "https://api.musixmatch.com/ws/1.1/track.lyrics.get")!
        lyrics.queryItems = [URLQueryItem(name: "track_id", value: String(trackID)),
                             URLQueryItem(name: "apikey", value: apiKey)]
        guard let lyricsURL = lyrics.url, let lyricsData = await httpGet(lyricsURL),
              let lyricsJSON = try? JSONSerialization.jsonObject(with: lyricsData) as? [String: Any],
              let lyricsMessage = lyricsJSON["message"] as? [String: Any],
              let lyricsBody = lyricsMessage["body"] as? [String: Any],
              let lyricsObject = lyricsBody["lyrics"] as? [String: Any],
              let text = lyricsObject["lyrics_body"] as? String else { return nil }
        // Shown as-is, including Musixmatch's own attribution line.
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    // MARK: - Cache: Caches/lyrics/<hash of song key>.lrc|.txt

    private static func cacheFile(_ song: Song, synced: Bool) -> URL {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("lyrics", isDirectory: true)
        let hash = SHA256.hash(data: Data(song.key.utf8)).map { String(format: "%02x", $0) }.joined()
        return folder.appendingPathComponent(hash + (synced ? ".lrc" : ".txt"))
    }

    private static func readCache(_ song: Song) -> LyricsContent? {
        for synced in [true, false] {
            if let text = try? String(contentsOf: cacheFile(song, synced: synced), encoding: .utf8), !text.isBlank {
                return LyricsContent.from(text, maybeSynced: synced)
            }
        }
        return nil
    }

    private static func writeCache(_ song: Song, text: String, synced: Bool) {
        let file = cacheFile(song, synced: synced)
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? text.write(to: file, atomically: true, encoding: .utf8)
    }

    /// Forgets cached lyrics for a song (after its title/artist were edited).
    static func clearCache(for song: Song) {
        for synced in [true, false] {
            try? FileManager.default.removeItem(at: cacheFile(song, synced: synced))
        }
    }

    private static func httpGet(_ url: URL) async -> Data? {
        var request = URLRequest(url: url, timeoutInterval: 15)
        // LRCLIB asks for an identifying User-Agent.
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        return data
    }
}

extension String {
    nonisolated var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
