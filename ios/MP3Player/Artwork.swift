import SwiftUI
import CryptoKit
import Combine
import ImageIO
import CoreImage
import UniformTypeIdentifiers

/// The playback position, kept apart from PlayerManager: it changes 5 times a
/// second, and only the progress bars and lyrics should redraw that often.
@MainActor
final class PlaybackClock: ObservableObject {
    @Published var time: TimeInterval = 0
}

/// Image work done once, off the main thread, and cached.
nonisolated enum ImageTools {
    /// Decodes `data` straight to an image no larger than `maxPixels` on its longest side
    /// (ImageIO thumbnailing: never decodes the full-size image).
    static func downsample(_ data: Data, maxPixels: Int) -> CGImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ] as CFDictionary
        return CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions)
    }

    /// Re-encodes cover art at most `maxPixels` big as JPEG (what the library keeps in memory).
    static func shrinkCover(_ data: Data, maxPixels: Int = 900) -> Data? {
        guard let image = downsample(data, maxPixels: maxPixels) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }

    /// A small, heavily blurred version of the cover for the Now Playing background.
    static func backdrop(_ data: Data) -> UIImage? {
        guard let small = downsample(data, maxPixels: 120) else { return nil }
        let input = CIImage(cgImage: small)
        guard let filter = CIFilter(name: "CIGaussianBlur") else { return nil }
        filter.setValue(input.clampedToExtent(), forKey: kCIInputImageKey)
        filter.setValue(12, forKey: kCIInputRadiusKey)
        guard let output = filter.outputImage?.cropped(to: input.extent),
              let blurred = CIContext().createCGImage(output, from: input.extent) else { return nil }
        return UIImage(cgImage: blurred)
    }
}

/// Decoded artwork, by song key and size. NSCache drops entries under memory pressure.
nonisolated final class ThumbnailCache: @unchecked Sendable {
    static let shared = ThumbnailCache()
    private let cache = NSCache<NSString, UIImage>()

    private init() {
        cache.countLimit = 600
    }

    func image(for key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func store(_ image: UIImage, for key: String) {
        cache.setObject(image, forKey: key as NSString)
    }

    /// Empties the cache.
    func removeAll() {
        cache.removeAllObjects()
    }
}

/// Cover art kept on disk (Caches/artwork), shrunk to ≤900 px, one file per distinct cover.
/// Songs only hold the id, so a big library doesn't keep thousands of images in memory.
nonisolated enum ArtworkStore {
    static let folder: URL = {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("artwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    static func url(for id: String) -> URL {
        folder.appendingPathComponent(id + ".jpg")
    }

    static func exists(_ id: String) -> Bool {
        FileManager.default.fileExists(atPath: url(for: id).path)
    }

    static func load(_ id: String?) -> Data? {
        guard let id else { return nil }
        return try? Data(contentsOf: url(for: id))
    }

    /// Stores a cover and returns its id. The id comes from the original bytes, so every
    /// song of an album shares one file, and an unchanged cover isn't shrunk again.
    static func store(_ original: Data) -> String? {
        let id = SHA256.hash(data: original).prefix(16).map { String(format: "%02x", $0) }.joined()
        if exists(id) { return id }
        let data = ImageTools.shrinkCover(original) ?? original
        do {
            try data.write(to: url(for: id), options: .atomic)
            return id
        } catch {
            return nil
        }
    }
}

/// Cover art at a fixed size. Decoded in the background at the size it's shown, and cached.
struct ArtworkView: View {
    var artworkID: String?
    /// Raw image data instead of a stored cover (the tag editor's preview). Not cached.
    var data: Data?
    let size: CGFloat
    var cornerRadius: CGFloat = 6

    init(artworkID: String?, size: CGFloat, cornerRadius: CGFloat = 6) {
        self.artworkID = artworkID
        self.size = size
        self.cornerRadius = cornerRadius
    }

    init(data: Data?, size: CGFloat, cornerRadius: CGFloat = 6) {
        self.data = data
        self.size = size
        self.cornerRadius = cornerRadius
    }

    var body: some View {
        CachedArtwork(artworkID: artworkID, data: data, pixelSize: size)
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

/// Cover art that fills a square of whatever width it's given (album grid, headers).
struct SquareArtwork: View {
    let artworkID: String?
    var cornerRadius: CGFloat = 8
    /// Points; the grid tiles are about this wide.
    var expectedSize: CGFloat = 200

    var body: some View {
        Color.gray.opacity(0.2)
            .aspectRatio(1, contentMode: .fit)
            .overlay { CachedArtwork(artworkID: artworkID, data: nil, pixelSize: expectedSize) }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

private struct CachedArtwork: View {
    let artworkID: String?
    let data: Data?
    let pixelSize: CGFloat
    @Environment(\.displayScale) private var scale
    @State private var loaded: (id: String, image: UIImage)?

    private var maxPixels: Int { Int(pixelSize * max(scale, 1)) }
    private var hasArtwork: Bool { artworkID != nil || data != nil }

    /// Changes when the artwork or the size changes.
    private var identity: String {
        "\(artworkID ?? "data:\(data?.count ?? 0)")|\(maxPixels)"
    }

    var body: some View {
        let id = identity
        let image = loaded?.id == id ? loaded?.image
            : (artworkID != nil ? ThumbnailCache.shared.image(for: id) : nil)

        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Color.gray.opacity(0.2)
                    if !hasArtwork {
                        Image(systemName: "music.note").foregroundStyle(.secondary)
                    }
                }
            }
        }
        .task(id: id) {
            guard hasArtwork, image == nil else { return }
            let pixels = maxPixels
            let artworkID = self.artworkID
            let data = self.data
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let bytes = data ?? ArtworkStore.load(artworkID) else { return nil }
                return ImageTools.downsample(bytes, maxPixels: pixels).map { UIImage(cgImage: $0) }
            }.value
            guard let decoded, !Task.isCancelled else { return }
            if artworkID != nil { ThumbnailCache.shared.store(decoded, for: id) }
            loaded = (id, decoded)
        }
    }
}
