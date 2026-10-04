import Foundation
import AVFoundation
import UIKit

/// Every tag the editor shows, plus what the library reads.
nonisolated struct TagFields: Equatable, Sendable {
    var title = ""
    var artist = ""
    var album = ""
    var albumArtist = ""
    var genre = ""
    var year = ""
    var track = ""
    var trackTotal = ""
    var disc = ""
    var discTotal = ""
    var composer = ""
    var bpm = ""
    var comment = ""
    /// Musical key ("Am", "C#") — ID3 TKEY / Vorbis INITIALKEY.
    var key = ""
    var artwork: Data?
    var replayGainDb: Float?

    var isEmpty: Bool { title.isEmpty && artist.isEmpty && album.isEmpty }

    /// Fills empty fields from `other`.
    mutating func fillGaps(from other: TagFields) {
        let keyPaths: [WritableKeyPath<TagFields, String>] = [
            \.title, \.artist, \.album, \.albumArtist, \.genre, \.year, \.track, \.trackTotal,
            \.disc, \.discTotal, \.composer, \.bpm, \.comment, \.key,
        ]
        for keyPath in keyPaths where self[keyPath: keyPath].isEmpty {
            self[keyPath: keyPath] = other[keyPath: keyPath]
        }
        if artwork == nil { artwork = other.artwork }
        if replayGainDb == nil { replayGainDb = other.replayGainDb }
    }
}

nonisolated enum TagError: LocalizedError {
    case unsupported(String)
    case unreadable
    case writeFailed(String)

    var errorDescription: String? {
        switch self {
        case .unsupported(let ext): "Editing tags isn't supported for .\(ext) files (MP3, FLAC and M4A are)."
        case .unreadable: "The file couldn't be read."
        case .writeFailed(let reason): "The tags couldn't be saved: \(reason)"
        }
    }
}

/// Reads and writes tags: ID3v2 (MP3) and FLAC are handled here byte by byte;
/// M4A goes through AVFoundation; everything else is read-only via AVFoundation.
nonisolated enum TagIO {
    static func canWrite(_ url: URL) -> Bool {
        ["mp3", "flac", "m4a", "m4b", "mp4"].contains(url.pathExtension.lowercased())
    }

    static func read(_ url: URL) async -> TagFields {
        var fields = TagFields()
        switch url.pathExtension.lowercased() {
        case "mp3":
            if let tag = try? ID3Tag.read(from: url) { fields = tag.fields() }
        case "flac":
            if let flac = try? FLACFile.read(from: url) { fields = flac.fields() }
        default:
            break
        }
        if fields.isEmpty || fields.artwork == nil {
            fields.fillGaps(from: await readWithAVFoundation(url))
        }
        return fields
    }

    /// Writes `fields` into the file. The cover is only touched when `artworkChanged`.
    static func write(_ fields: TagFields, to url: URL, artworkChanged: Bool) async throws {
        switch url.pathExtension.lowercased() {
        case "mp3":
            var tag = (try? ID3Tag.read(from: url)) ?? ID3Tag.empty
            tag.apply(fields, artworkChanged: artworkChanged)
            let original = try Data(contentsOf: url, options: .mappedIfSafe)
            var output = tag.serialize()
            output.append(original.subdata(in: min(tag.audioOffset, original.count)..<original.count))
            try replaceContents(of: url, with: output)
        case "flac":
            var flac = try FLACFile.read(from: url)
            flac.apply(fields, artworkChanged: artworkChanged)
            let original = try Data(contentsOf: url, options: .mappedIfSafe)
            var output = try flac.serializeHeader()
            output.append(original.subdata(in: min(flac.audioOffset, original.count)..<original.count))
            try replaceContents(of: url, with: output)
        case "m4a", "m4b", "mp4":
            try await writeM4A(fields, to: url, artworkChanged: artworkChanged)
        default:
            throw TagError.unsupported(url.pathExtension.lowercased())
        }
    }

    /// Scales a picked image down to at most 1200 px and re-encodes it as JPEG.
    static func prepareCover(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let maxSide: CGFloat = 1200
        let size = image.size
        let scale = min(1, maxSide / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: 0.9)
    }

    // MARK: - File replacement

    /// Writes next to the file first, then swaps it in, so a failed write never truncates the song.
    private static func replaceContents(of url: URL, with data: Data) throws {
        let temp = url.deletingLastPathComponent()
            .appendingPathComponent(".\(UUID().uuidString).\(url.pathExtension)")
        do {
            try data.write(to: temp)
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temp)
        } catch {
            try? FileManager.default.removeItem(at: temp)
            // Some file providers don't allow a temp file next to the song: write in place.
            do {
                try data.write(to: url)
            } catch {
                throw TagError.writeFailed(error.localizedDescription)
            }
        }
    }

    // MARK: - AVFoundation (M4A and anything else)

    private static func readWithAVFoundation(_ url: URL) async -> TagFields {
        var fields = TagFields()
        let asset = AVURLAsset(url: url)
        let common = (try? await asset.load(.commonMetadata)) ?? []
        let all = (try? await asset.load(.metadata)) ?? []

        func string(_ item: AVMetadataItem) async -> String {
            if let value = try? await item.load(.stringValue) {
                return value.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if let number = try? await item.load(.numberValue) { return number.stringValue }
            return ""
        }
        func set(_ keyPath: WritableKeyPath<TagFields, String>, _ item: AVMetadataItem) async {
            guard fields[keyPath: keyPath].isEmpty else { return }
            fields[keyPath: keyPath] = await string(item)
        }

        for item in common + all {
            guard let identifier = item.identifier else { continue }
            switch identifier {
            case .commonIdentifierTitle, .iTunesMetadataSongName, .id3MetadataTitleDescription,
                 .quickTimeMetadataTitle:
                await set(\.title, item)
            case .commonIdentifierArtist, .iTunesMetadataArtist, .id3MetadataLeadPerformer,
                 .quickTimeMetadataArtist:
                await set(\.artist, item)
            case .commonIdentifierAlbumName, .iTunesMetadataAlbum, .id3MetadataAlbumTitle,
                 .quickTimeMetadataAlbum:
                await set(\.album, item)
            case .iTunesMetadataAlbumArtist, .id3MetadataBand:
                await set(\.albumArtist, item)
            case .iTunesMetadataUserGenre, .id3MetadataContentType, .quickTimeMetadataGenre:
                await set(\.genre, item)
            case .iTunesMetadataReleaseDate, .id3MetadataYear, .id3MetadataRecordingTime,
                 .commonIdentifierCreationDate, .quickTimeMetadataYear:
                if fields.year.isEmpty { fields.year = String(await string(item).prefix(4)) }
            case .iTunesMetadataComposer, .id3MetadataComposer, .quickTimeMetadataComposer:
                await set(\.composer, item)
            case .id3MetadataInitialKey:
                await set(\.key, item)
            case .iTunesMetadataBeatsPerMin, .id3MetadataBeatsPerMinute:
                await set(\.bpm, item)
            case .iTunesMetadataUserComment, .id3MetadataComments, .quickTimeMetadataComment:
                await set(\.comment, item)
            case .iTunesMetadataTrackNumber, .iTunesMetadataDiscNumber:
                let isTrack = identifier == .iTunesMetadataTrackNumber
                if let data = try? await item.load(.dataValue), data.count >= 6 {
                    let bytes = [UInt8](data)
                    let number = Int(bytes[2]) << 8 | Int(bytes[3])
                    let total = Int(bytes[4]) << 8 | Int(bytes[5])
                    if isTrack, fields.track.isEmpty {
                        fields.track = number > 0 ? "\(number)" : ""
                        fields.trackTotal = total > 0 ? "\(total)" : ""
                    } else if !isTrack, fields.disc.isEmpty {
                        fields.disc = number > 0 ? "\(number)" : ""
                        fields.discTotal = total > 0 ? "\(total)" : ""
                    }
                }
            case .id3MetadataTrackNumber:
                if fields.track.isEmpty {
                    (fields.track, fields.trackTotal) = splitNumberPair(await string(item))
                }
            case .id3MetadataPartOfASet:
                if fields.disc.isEmpty {
                    (fields.disc, fields.discTotal) = splitNumberPair(await string(item))
                }
            case .commonIdentifierArtwork, .iTunesMetadataCoverArt, .id3MetadataAttachedPicture:
                if fields.artwork == nil { fields.artwork = try? await item.load(.dataValue) }
            default:
                if fields.replayGainDb == nil,
                   identifier.rawValue.lowercased().contains("replaygain_track_gain") {
                    fields.replayGainDb = parseReplayGainDb(await string(item))
                }
            }
        }
        return fields
    }

    private static func writeM4A(_ fields: TagFields, to url: URL, artworkChanged: Bool) async throws {
        let asset = AVURLAsset(url: url)
        let existing = (try? await asset.load(.metadata)) ?? []
        var managed: Set<AVMetadataIdentifier> = [
            .iTunesMetadataSongName, .iTunesMetadataArtist, .iTunesMetadataAlbum, .iTunesMetadataAlbumArtist,
            .iTunesMetadataUserGenre, .iTunesMetadataReleaseDate, .iTunesMetadataTrackNumber,
            .iTunesMetadataDiscNumber, .iTunesMetadataComposer, .iTunesMetadataBeatsPerMin,
            .iTunesMetadataUserComment,
        ]
        if artworkChanged { managed.insert(.iTunesMetadataCoverArt) }

        var items = existing.filter { item in
            guard let identifier = item.identifier else { return true }
            if managed.contains(identifier) { return false }
            if artworkChanged && identifier == .commonIdentifierArtwork { return false }
            return true
        }

        func add(_ identifier: AVMetadataIdentifier, _ value: (NSCopying & NSObjectProtocol)?,
                 dataType: CFString? = nil) {
            guard let value else { return }
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value
            if let dataType { item.dataType = dataType as String }
            items.append(item)
        }
        func text(_ value: String) -> NSString? {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed as NSString
        }
        func pair(_ number: String, _ total: String, length: Int) -> NSData? {
            guard let n = Int(number), n > 0 else { return nil }
            let t = Int(total) ?? 0
            var bytes: [UInt8] = [0, 0, UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF),
                                  UInt8((t >> 8) & 0xFF), UInt8(t & 0xFF)]
            if length == 8 { bytes += [0, 0] }
            return Data(bytes) as NSData
        }

        add(.iTunesMetadataSongName, text(fields.title))
        add(.iTunesMetadataArtist, text(fields.artist))
        add(.iTunesMetadataAlbum, text(fields.album))
        add(.iTunesMetadataAlbumArtist, text(fields.albumArtist))
        add(.iTunesMetadataUserGenre, text(fields.genre))
        add(.iTunesMetadataReleaseDate, text(fields.year))
        add(.iTunesMetadataComposer, text(fields.composer))
        add(.iTunesMetadataUserComment, text(fields.comment))
        add(.iTunesMetadataTrackNumber, pair(fields.track, fields.trackTotal, length: 8))
        add(.iTunesMetadataDiscNumber, pair(fields.disc, fields.discTotal, length: 6))
        if let bpm = Int(fields.bpm.trimmingCharacters(in: .whitespaces)), bpm > 0 {
            add(.iTunesMetadataBeatsPerMin, NSNumber(value: Int16(clamping: bpm)),
                dataType: kCMMetadataBaseDataType_SInt16)
        }
        if artworkChanged, let artwork = fields.artwork {
            add(.iTunesMetadataCoverArt, artwork as NSData, dataType: kCMMetadataBaseDataType_JPEG)
        }

        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw TagError.writeFailed("export not available")
        }
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).m4a")
        session.outputURL = temp
        session.outputFileType = .m4a
        session.metadata = items
        await session.export()
        guard session.status == .completed else {
            try? FileManager.default.removeItem(at: temp)
            throw TagError.writeFailed(session.error?.localizedDescription ?? "export failed")
        }
        defer { try? FileManager.default.removeItem(at: temp) }
        let data = try Data(contentsOf: temp)
        try replaceContents(of: url, with: data)
    }

    /// "3/12" -> ("3", "12").
    static func splitNumberPair(_ value: String) -> (String, String) {
        let parts = value.split(separator: "/", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
        let number = parts.first.flatMap { Int($0) }.map(String.init) ?? ""
        let total = parts.count > 1 ? (Int(parts[1]).map(String.init) ?? "") : ""
        return (number, total)
    }

    static func joinNumberPair(_ number: String, _ total: String) -> String {
        let n = number.trimmingCharacters(in: .whitespaces)
        let t = total.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return "" }
        return t.isEmpty ? n : "\(n)/\(t)"
    }
}

// MARK: - Byte helpers

nonisolated private func be16(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) << 8 | Int(b[i + 1]) }
nonisolated private func be24(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) << 16 | Int(b[i + 1]) << 8 | Int(b[i + 2]) }
nonisolated private func be32(_ b: [UInt8], _ i: Int) -> Int {
    Int(b[i]) << 24 | Int(b[i + 1]) << 16 | Int(b[i + 2]) << 8 | Int(b[i + 3])
}
nonisolated private func le32(_ b: [UInt8], _ i: Int) -> Int {
    Int(b[i]) | Int(b[i + 1]) << 8 | Int(b[i + 2]) << 16 | Int(b[i + 3]) << 24
}
nonisolated private func syncsafe32(_ b: [UInt8], _ i: Int) -> Int {
    Int(b[i] & 0x7F) << 21 | Int(b[i + 1] & 0x7F) << 14 | Int(b[i + 2] & 0x7F) << 7 | Int(b[i + 3] & 0x7F)
}
nonisolated private func bytesBE32(_ v: Int) -> [UInt8] {
    [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
}
nonisolated private func bytesLE32(_ v: Int) -> [UInt8] {
    [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
}
nonisolated private func bytesSyncsafe32(_ v: Int) -> [UInt8] {
    [UInt8((v >> 21) & 0x7F), UInt8((v >> 14) & 0x7F), UInt8((v >> 7) & 0x7F), UInt8(v & 0x7F)]
}

/// Reads `count` bytes at `offset` (fewer at the end of the file).
nonisolated private func readBytes(_ handle: FileHandle, at offset: Int, count: Int) throws -> [UInt8] {
    try handle.seek(toOffset: UInt64(offset))
    return [UInt8](try handle.read(upToCount: count) ?? Data())
}

/// Size of an ID3v2 tag at the start of `header` (10 bytes), including any footer, or 0.
nonisolated private func id3TagLength(_ header: [UInt8]) -> Int {
    guard header.count >= 10, header[0] == 0x49, header[1] == 0x44, header[2] == 0x33 else { return 0 }
    let footer = header[3] == 4 && header[5] & 0x10 != 0 ? 10 : 0
    return 10 + syncsafe32(header, 6) + footer
}

// MARK: - ID3v2

nonisolated struct ID3Frame {
    var id: String
    var flags: Int
    var body: [UInt8]
}

/// An ID3v2.2/2.3/2.4 tag. Written back as 2.3, or 2.4 if it already was 2.4
/// (frames the editor doesn't manage are kept as they were).
nonisolated struct ID3Tag {
    var major: Int
    var frames: [ID3Frame]
    /// Where the audio starts in the original file (0 if there was no tag).
    var audioOffset: Int

    static let empty = ID3Tag(major: 3, frames: [], audioOffset: 0)

    static func read(from url: URL) throws -> ID3Tag {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let header = try readBytes(handle, at: 0, count: 10)
        let length = id3TagLength(header)
        guard length > 0 else { return .empty }
        let major = Int(header[3])
        let flags = Int(header[5])
        var body = try readBytes(handle, at: 10, count: syncsafe32(header, 6))
        if major <= 3 && flags & 0x80 != 0 { body = deunsynchronise(body) }

        var pos = 0
        if flags & 0x40 != 0 && body.count >= 4 {
            pos = major == 3 ? 4 + be32(body, 0) : (major == 4 ? syncsafe32(body, 0) : 0)
        }
        let idLength = major == 2 ? 3 : 4
        let headerLength = major == 2 ? 6 : 10
        var frames: [ID3Frame] = []
        while pos >= 0 && pos + headerLength <= body.count {
            let idBytes = Array(body[pos..<pos + idLength])
            guard idBytes.allSatisfy({ (0x30...0x39).contains($0) || (0x41...0x5A).contains($0) }) else { break }
            let id = String(decoding: idBytes, as: UTF8.self)
            let size: Int
            switch major {
            case 2: size = be24(body, pos + 3)
            case 4: size = syncsafe32(body, pos + 4)
            default: size = be32(body, pos + 4)
            }
            let frameFlags = major == 2 ? 0 : be16(body, pos + 8)
            pos += headerLength
            guard size >= 0, pos + size <= body.count else { break }
            frames.append(ID3Frame(id: id, flags: frameFlags, body: Array(body[pos..<pos + size])))
            pos += size
        }
        return ID3Tag(major: major, frames: frames, audioOffset: length)
    }

    // MARK: Reading values

    func fields() -> TagFields {
        var f = TagFields()
        let v2 = major == 2
        f.title = text(v2 ? "TT2" : "TIT2")
        f.artist = text(v2 ? "TP1" : "TPE1")
        f.album = text(v2 ? "TAL" : "TALB")
        f.albumArtist = text(v2 ? "TP2" : "TPE2")
        f.genre = Self.cleanGenre(text(v2 ? "TCO" : "TCON"))
        f.year = String((v2 ? text("TYE") : (text("TDRC").isEmpty ? text("TYER") : text("TDRC"))).prefix(4))
        (f.track, f.trackTotal) = TagIO.splitNumberPair(text(v2 ? "TRK" : "TRCK"))
        (f.disc, f.discTotal) = TagIO.splitNumberPair(text(v2 ? "TPA" : "TPOS"))
        f.composer = text(v2 ? "TCM" : "TCOM")
        f.bpm = text(v2 ? "TBP" : "TBPM")
        f.key = text(v2 ? "TKE" : "TKEY")
        f.comment = comment()
        f.artwork = picture()
        for frame in frames where frame.id == (v2 ? "TXX" : "TXXX") {
            guard let body = readable(frame), body.count > 1 else { continue }
            let (description, next) = Self.terminatedString(body, from: 1, encoding: body[0])
            if description.caseInsensitiveCompare("REPLAYGAIN_TRACK_GAIN") == .orderedSame {
                f.replayGainDb = parseReplayGainDb(Self.decode(Array(body[next...]), encoding: body[0]))
            }
        }
        return f
    }

    private func text(_ id: String) -> String {
        guard let frame = frames.first(where: { $0.id == id }), let body = readable(frame), !body.isEmpty
        else { return "" }
        return Self.decode(Array(body[1...]), encoding: body[0])
    }

    private func comment() -> String {
        var fallback = ""
        for frame in frames where frame.id == (major == 2 ? "COM" : "COMM") {
            guard let body = readable(frame), body.count > 4 else { continue }
            let (description, next) = Self.terminatedString(body, from: 4, encoding: body[0])
            let value = Self.decode(Array(body[min(next, body.count)...]), encoding: body[0])
            if description.isEmpty { return value }
            if fallback.isEmpty && !description.hasPrefix("iTun") { fallback = value }
        }
        return fallback
    }

    private func picture() -> Data? {
        var first: Data?
        for frame in frames where frame.id == (major == 2 ? "PIC" : "APIC") {
            guard let body = readable(frame), body.count > 4 else { continue }
            let encoding = body[0]
            var pos: Int
            if major == 2 {
                pos = 4                                   // encoding + 3-letter format
            } else {
                pos = 1
                while pos < body.count && body[pos] != 0 { pos += 1 }   // MIME type
                pos += 1
            }
            guard pos < body.count else { continue }
            let type = body[pos]
            let (_, dataStart) = Self.terminatedString(body, from: pos + 1, encoding: encoding)
            guard dataStart < body.count else { continue }
            let data = Data(body[dataStart...])
            if type == 3 { return data }                  // front cover
            if first == nil { first = data }
        }
        return first
    }

    /// The frame body with per-frame unsync/length prefixes undone; nil for compressed/encrypted frames.
    private func readable(_ frame: ID3Frame) -> [UInt8]? {
        Self.readable(frame, major: major)
    }

    private static func readable(_ frame: ID3Frame, major: Int) -> [UInt8]? {
        var body = frame.body
        if major == 4 {
            if frame.flags & 0x000C != 0 { return nil }
            if frame.flags & 0x0002 != 0 { body = Self.deunsynchronise(body) }
            if frame.flags & 0x0001 != 0 { body = Array(body.dropFirst(4)) }
        } else if major == 3 {
            if frame.flags & 0x00C0 != 0 { return nil }
            if frame.flags & 0x0020 != 0 { body = Array(body.dropFirst(1)) }
        }
        return body
    }

    // MARK: Writing

    mutating func apply(_ fields: TagFields, artworkChanged: Bool) {
        if major != 3 && major != 4 {
            // 2.2 frames can't be written into a 2.3 tag as they are: start fresh.
            frames = []
            major = 3
        }
        var managed: Set<String> = ["TIT2", "TPE1", "TALB", "TPE2", "TCON", "TYER", "TDAT", "TDRC",
                                    "TRCK", "TPOS", "TCOM", "TBPM", "TKEY"]
        if artworkChanged { managed.insert("APIC") }
        let major = self.major
        frames.removeAll { frame in
            if managed.contains(frame.id) { return true }
            if frame.id == "COMM", let body = Self.readable(frame, major: major), body.count > 4 {
                return Self.terminatedString(body, from: 4, encoding: body[0]).0.isEmpty
            }
            return false
        }

        func addText(_ id: String, _ value: String) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            let (encoding, bytes) = encode(trimmed)
            frames.append(ID3Frame(id: id, flags: 0, body: [encoding] + bytes))
        }
        addText("TIT2", fields.title)
        addText("TPE1", fields.artist)
        addText("TALB", fields.album)
        addText("TPE2", fields.albumArtist)
        addText("TCON", fields.genre)
        addText(major == 4 ? "TDRC" : "TYER", fields.year)
        addText("TRCK", TagIO.joinNumberPair(fields.track, fields.trackTotal))
        addText("TPOS", TagIO.joinNumberPair(fields.disc, fields.discTotal))
        addText("TCOM", fields.composer)
        addText("TBPM", fields.bpm)
        addText("TKEY", fields.key)

        let comment = fields.comment.trimmingCharacters(in: .whitespacesAndNewlines)
        if !comment.isEmpty {
            let (encoding, bytes) = encode(comment)
            let terminator: [UInt8] = encoding == 1 ? [0xFF, 0xFE, 0, 0] : (encoding == 0 || encoding == 3 ? [0] : [0, 0])
            frames.append(ID3Frame(id: "COMM", flags: 0, body: [encoding] + Array("eng".utf8) + terminator + bytes))
        }
        if artworkChanged, let artwork = fields.artwork {
            let mime = artwork.starts(with: [0x89, 0x50, 0x4E, 0x47]) ? "image/png" : "image/jpeg"
            let body: [UInt8] = [0] + Array(mime.utf8) + [0, 3, 0] + [UInt8](artwork)
            frames.append(ID3Frame(id: "APIC", flags: 0, body: body))
        }
    }

    func serialize(padding: Int = 1024) -> Data {
        var tag: [UInt8] = []
        for frame in frames {
            tag += Array(frame.id.utf8)
            tag += major == 4 ? bytesSyncsafe32(frame.body.count) : bytesBE32(frame.body.count)
            tag += [UInt8((frame.flags >> 8) & 0xFF), UInt8(frame.flags & 0xFF)]
            tag += frame.body
        }
        tag += [UInt8](repeating: 0, count: padding)
        let header: [UInt8] = [0x49, 0x44, 0x33, UInt8(major), 0, 0] + bytesSyncsafe32(tag.count)
        return Data(header + tag)
    }

    /// ISO-8859-1 when possible, else UTF-8 (2.4) or UTF-16 with BOM (2.3).
    private func encode(_ value: String) -> (UInt8, [UInt8]) {
        if value.unicodeScalars.allSatisfy({ $0.value < 0x100 }) {
            return (0, value.unicodeScalars.map { UInt8($0.value) })
        }
        if major == 4 { return (3, Array(value.utf8)) }
        var bytes: [UInt8] = [0xFF, 0xFE]
        for unit in value.utf16 { bytes += [UInt8(unit & 0xFF), UInt8(unit >> 8)] }
        return (1, bytes)
    }

    // MARK: Helpers

    static func decode(_ bytes: [UInt8], encoding: UInt8) -> String {
        let data = Data(bytes)
        let string: String?
        switch encoding {
        case 1: string = String(data: data, encoding: .utf16)
        case 2: string = String(data: data, encoding: .utf16BigEndian)
        case 3: string = String(data: data, encoding: .utf8)
        default: string = String(data: data, encoding: .isoLatin1)
        }
        // 2.4 can hold several null-separated values: keep the first.
        let first = (string ?? "").split(separator: "\u{0}", omittingEmptySubsequences: true).first
        return first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    /// A null-terminated string starting at `start`, and the index just past its terminator.
    static func terminatedString(_ body: [UInt8], from start: Int, encoding: UInt8) -> (String, Int) {
        guard start < body.count else { return ("", body.count) }
        if encoding == 1 || encoding == 2 {
            var i = start
            while i + 1 < body.count {
                if body[i] == 0 && body[i + 1] == 0 {
                    return (decode(Array(body[start..<i]), encoding: encoding), i + 2)
                }
                i += 2
            }
            return (decode(Array(body[start...]), encoding: encoding), body.count)
        }
        var i = start
        while i < body.count && body[i] != 0 { i += 1 }
        return (decode(Array(body[start..<i]), encoding: encoding), min(i + 1, body.count))
    }

    static func deunsynchronise(_ bytes: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        var i = 0
        while i < bytes.count {
            out.append(bytes[i])
            if bytes[i] == 0xFF && i + 1 < bytes.count && bytes[i + 1] == 0 { i += 1 }
            i += 1
        }
        return out
    }

    /// "(17)", "17", "(17)Rock" -> "Rock".
    static func cleanGenre(_ value: String) -> String {
        var genre = value
        if genre.hasPrefix("("), let close = genre.firstIndex(of: ")") {
            let number = genre[genre.index(after: genre.startIndex)..<close]
            let rest = genre[genre.index(after: close)...].trimmingCharacters(in: .whitespaces)
            if !rest.isEmpty { return rest }
            genre = String(number)
        }
        if let index = Int(genre), id3v1Genres.indices.contains(index) { return id3v1Genres[index] }
        return genre
    }

    static let id3v1Genres = [
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
        "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
        "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
        "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
        "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka",
        "Retro", "Musical", "Rock & Roll", "Hard Rock",
    ]
}

// MARK: - FLAC

/// A FLAC file's metadata blocks (STREAMINFO, VORBIS_COMMENT, PICTURE, ...).
nonisolated struct FLACFile {
    nonisolated struct Block {
        var type: UInt8
        var data: [UInt8]
    }

    /// An ID3 tag some taggers put before "fLaC" (kept as-is).
    var prefix: [UInt8]
    var blocks: [Block]
    /// Where the audio frames start in the original file.
    var audioOffset: Int

    static func read(from url: URL) throws -> FLACFile {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let prefixLength = id3TagLength(try readBytes(handle, at: 0, count: 10))
        let prefix = try readBytes(handle, at: 0, count: prefixLength)
        guard try readBytes(handle, at: prefixLength, count: 4) == Array("fLaC".utf8) else {
            throw TagError.unreadable
        }
        var pos = prefixLength + 4
        var blocks: [Block] = []
        while true {
            let header = try readBytes(handle, at: pos, count: 4)
            guard header.count == 4 else { throw TagError.unreadable }
            let length = be24(header, 1)
            let data = try readBytes(handle, at: pos + 4, count: length)
            guard data.count == length else { throw TagError.unreadable }
            blocks.append(Block(type: header[0] & 0x7F, data: data))
            pos += 4 + length
            if header[0] & 0x80 != 0 { break }
        }
        return FLACFile(prefix: prefix, blocks: blocks, audioOffset: pos)
    }

    // MARK: Vorbis comments

    private var commentBlock: (vendor: [UInt8], comments: [String])? {
        guard let block = blocks.first(where: { $0.type == 4 }) else { return nil }
        let b = block.data
        guard b.count >= 8 else { return nil }
        let vendorLength = le32(b, 0)
        guard 4 + vendorLength + 4 <= b.count else { return nil }
        let vendor = Array(b[4..<4 + vendorLength])
        var pos = 4 + vendorLength
        let count = le32(b, pos)
        pos += 4
        var comments: [String] = []
        for _ in 0..<count {
            guard pos + 4 <= b.count else { break }
            let length = le32(b, pos)
            pos += 4
            guard length >= 0, pos + length <= b.count else { break }
            comments.append(String(decoding: b[pos..<pos + length], as: UTF8.self))
            pos += length
        }
        return (vendor, comments)
    }

    func fields() -> TagFields {
        var values: [String: String] = [:]
        for comment in commentBlock?.comments ?? [] {
            guard let equals = comment.firstIndex(of: "=") else { continue }
            let key = comment[..<equals].uppercased()
            if values[key] == nil { values[key] = String(comment[comment.index(after: equals)...]) }
        }
        var f = TagFields()
        f.title = values["TITLE"] ?? ""
        f.artist = values["ARTIST"] ?? ""
        f.album = values["ALBUM"] ?? ""
        f.albumArtist = values["ALBUMARTIST"] ?? values["ALBUM ARTIST"] ?? ""
        f.genre = values["GENRE"] ?? ""
        f.year = String((values["DATE"] ?? values["YEAR"] ?? "").prefix(4))
        let track = TagIO.splitNumberPair(values["TRACKNUMBER"] ?? "")
        f.track = track.0
        f.trackTotal = values["TRACKTOTAL"] ?? values["TOTALTRACKS"] ?? track.1
        let disc = TagIO.splitNumberPair(values["DISCNUMBER"] ?? "")
        f.disc = disc.0
        f.discTotal = values["DISCTOTAL"] ?? values["TOTALDISCS"] ?? disc.1
        f.composer = values["COMPOSER"] ?? ""
        f.bpm = values["BPM"] ?? ""
        f.key = values["INITIALKEY"] ?? values["KEY"] ?? ""
        f.comment = values["COMMENT"] ?? values["DESCRIPTION"] ?? ""
        f.replayGainDb = parseReplayGainDb(values["REPLAYGAIN_TRACK_GAIN"])
        f.artwork = picture()
        return f
    }

    private func picture() -> Data? {
        var first: Data?
        for block in blocks where block.type == 6 {
            let b = block.data
            guard b.count >= 32 else { continue }
            let type = be32(b, 0)
            var pos = 4
            pos += 4 + be32(b, pos)                       // MIME type
            guard pos + 4 <= b.count else { continue }
            pos += 4 + be32(b, pos)                       // description
            pos += 16                                     // width, height, depth, colours
            guard pos + 4 <= b.count else { continue }
            let length = be32(b, pos)
            pos += 4
            guard length > 0, pos + length <= b.count else { continue }
            let data = Data(b[pos..<pos + length])
            if type == 3 { return data }
            if first == nil { first = data }
        }
        return first
    }

    // MARK: Writing

    mutating func apply(_ fields: TagFields, artworkChanged: Bool) {
        let managed: Set<String> = ["TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "ALBUM ARTIST", "GENRE", "DATE",
                                    "YEAR", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER", "DISCTOTAL",
                                    "TOTALDISCS", "COMPOSER", "BPM", "COMMENT", "DESCRIPTION", "INITIALKEY", "KEY"]
        let existing = commentBlock
        var comments = (existing?.comments ?? []).filter { comment in
            let key = comment.split(separator: "=", maxSplits: 1).first.map { $0.uppercased() } ?? ""
            if managed.contains(key) { return false }
            if artworkChanged && key == "METADATA_BLOCK_PICTURE" { return false }
            return true
        }
        func add(_ key: String, _ value: String) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { comments.append("\(key)=\(trimmed)") }
        }
        add("TITLE", fields.title)
        add("ARTIST", fields.artist)
        add("ALBUM", fields.album)
        add("ALBUMARTIST", fields.albumArtist)
        add("GENRE", fields.genre)
        add("DATE", fields.year)
        add("TRACKNUMBER", fields.track)
        add("TRACKTOTAL", fields.trackTotal)
        add("DISCNUMBER", fields.disc)
        add("DISCTOTAL", fields.discTotal)
        add("COMPOSER", fields.composer)
        add("BPM", fields.bpm)
        add("INITIALKEY", fields.key)
        add("COMMENT", fields.comment)

        let vendor = existing?.vendor ?? Array("AudioForge".utf8)
        var data = bytesLE32(vendor.count) + vendor + bytesLE32(comments.count)
        for comment in comments {
            let bytes = Array(comment.utf8)
            data += bytesLE32(bytes.count) + bytes
        }
        let newCommentBlock = Block(type: 4, data: data)

        if let index = blocks.firstIndex(where: { $0.type == 4 }) {
            blocks[index] = newCommentBlock
            blocks.removeAll { $0.type == 4 && $0.data != data }
        } else {
            blocks.insert(newCommentBlock, at: min(1, blocks.count))
        }

        if artworkChanged {
            blocks.removeAll { $0.type == 6 }
            if let artwork = fields.artwork {
                let image = UIImage(data: artwork)
                let mime = Array((artwork.starts(with: [0x89, 0x50, 0x4E, 0x47]) ? "image/png" : "image/jpeg").utf8)
                var picture = bytesBE32(3) + bytesBE32(mime.count) + mime + bytesBE32(0)
                picture += bytesBE32(Int(image?.size.width ?? 0)) + bytesBE32(Int(image?.size.height ?? 0))
                picture += bytesBE32(24) + bytesBE32(0)
                picture += bytesBE32(artwork.count) + [UInt8](artwork)
                blocks.append(Block(type: 6, data: picture))
            }
        }
        blocks.removeAll { $0.type == 1 }                 // old padding; fresh padding is added on write
    }

    /// Everything before the audio frames: prefix, "fLaC", the blocks and some padding.
    func serializeHeader(padding: Int = 4096) throws -> Data {
        var all = blocks
        all.append(Block(type: 1, data: [UInt8](repeating: 0, count: padding)))
        var out = prefix + Array("fLaC".utf8)
        for (index, block) in all.enumerated() {
            guard block.data.count < 1 << 24 else { throw TagError.writeFailed("a metadata block is too large") }
            let last: UInt8 = index == all.count - 1 ? 0x80 : 0
            let length = block.data.count
            out += [block.type | last, UInt8((length >> 16) & 0xFF), UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)]
            out += block.data
        }
        return Data(out)
    }
}
