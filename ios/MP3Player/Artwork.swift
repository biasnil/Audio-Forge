import SwiftUI
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

/// Cover art at a fixed size. Decoded in the background at the size it's shown, and cached.
struct ArtworkView: View {
    let data: Data?
    let size: CGFloat
    /// Identifies the artwork for the cache (usually the song key). nil = decode, don't cache.
    var cacheKey: String?
    var cornerRadius: CGFloat = 6

    var body: some View {
        CachedArtwork(data: data, pixelSize: size, cacheKey: cacheKey)
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

/// Cover art that fills a square of whatever width it's given (album grid, headers).
struct SquareArtwork: View {
    let data: Data?
    var cacheKey: String?
    var cornerRadius: CGFloat = 8
    /// Points; the grid tiles are about this wide.
    var expectedSize: CGFloat = 200

    var body: some View {
        Color.gray.opacity(0.2)
            .aspectRatio(1, contentMode: .fit)
            .overlay { CachedArtwork(data: data, pixelSize: expectedSize, cacheKey: cacheKey) }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

private struct CachedArtwork: View {
    let data: Data?
    let pixelSize: CGFloat
    let cacheKey: String?
    @Environment(\.displayScale) private var scale
    @State private var loaded: (id: String, image: UIImage)?

    private var maxPixels: Int { Int(pixelSize * max(scale, 1)) }

    /// Changes when the artwork or the size changes.
    private var identity: String {
        "\(cacheKey ?? "-")|\(data?.count ?? 0)|\(maxPixels)"
    }

    var body: some View {
        let id = identity
        let image = loaded?.id == id ? loaded?.image
            : (cacheKey != nil ? ThumbnailCache.shared.image(for: id) : nil)

        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Color.gray.opacity(0.2)
                    if data == nil {
                        Image(systemName: "music.note").foregroundStyle(.secondary)
                    }
                }
            }
        }
        .task(id: id) {
            guard let data, image == nil else { return }
            let pixels = maxPixels
            let decoded = await Task.detached(priority: .userInitiated) {
                ImageTools.downsample(data, maxPixels: pixels).map { UIImage(cgImage: $0) }
            }.value
            guard let decoded, !Task.isCancelled else { return }
            if cacheKey != nil { ThumbnailCache.shared.store(decoded, for: id) }
            loaded = (id, decoded)
        }
    }
}
