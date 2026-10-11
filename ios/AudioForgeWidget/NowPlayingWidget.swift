import WidgetKit
import SwiftUI
import AppIntents

@main
struct AudioForgeWidgets: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
    }
}

/// Home screen widget: the current song with play/pause and next (small) or
/// previous/play/next (medium). Tapping anywhere else opens the app.
struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: NowPlayingShared.widgetKind, provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(entry: entry)
        }
        .configurationDisplayName("Now Playing")
        .description("The current song, with play/pause and skip.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

nonisolated struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let snapshot: NowPlayingSnapshot?
    let cover: UIImage?
}

/// One entry, never refreshed on a schedule: the app asks for a reload when something changes.
nonisolated struct NowPlayingProvider: TimelineProvider {
    func placeholder(in context: Context) -> NowPlayingEntry {
        NowPlayingEntry(date: .now,
                        snapshot: NowPlayingSnapshot(title: "Song Title", artist: "Artist", isPlaying: false),
                        cover: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) {
        let entry = current()
        completion(context.isPreview && entry.snapshot == nil ? placeholder(in: context) : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        completion(Timeline(entries: [current()], policy: .never))
    }

    private func current() -> NowPlayingEntry {
        let snapshot = NowPlayingShared.load()
        let cover = snapshot?.artworkID == nil ? nil
            : NowPlayingShared.coverURL.flatMap { UIImage(contentsOfFile: $0.path) }
        return NowPlayingEntry(date: .now, snapshot: snapshot, cover: cover)
    }
}

struct NowPlayingWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NowPlayingEntry

    private var title: String { entry.snapshot?.title ?? "Not Playing" }
    private var artist: String { entry.snapshot?.artist ?? "AudioForge" }
    private var isPlaying: Bool { entry.snapshot?.isPlaying ?? false }

    var body: some View {
        Group {
            if family == .systemMedium { medium } else { small }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .top) {
                cover(size: 58)
                Spacer(minLength: 4)
                playPauseButton(size: 40)
            }
            Spacer(minLength: 4)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
            HStack(spacing: 4) {
                Text(artist)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Button(intent: NextTrackIntent()) {
                    Image(systemName: "forward.fill")
                        .font(.body)
                        .frame(width: 30, height: 26)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var medium: some View {
        HStack(spacing: 14) {
            cover(size: 116)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .lineLimit(2)
                Text(artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 6)
                HStack(spacing: 0) {
                    Button(intent: PreviousTrackIntent()) {
                        Image(systemName: "backward.fill")
                            .font(.title3)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    playPauseButton(size: 44)
                        .frame(maxWidth: .infinity)
                    Button(intent: NextTrackIntent()) {
                        Image(systemName: "forward.fill")
                            .font(.title3)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func playPauseButton(size: CGFloat) -> some View {
        Button(intent: PlayPauseIntent()) {
            Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.background)
                .frame(width: size, height: size)
                .background(Circle().fill(.primary))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isPlaying ? "Pause" : "Play")
    }

    private func cover(size: CGFloat) -> some View {
        ZStack {
            if let cover = entry.cover {
                Image(uiImage: cover)
                    .resizable()
                    .scaledToFill()
            } else {
                Rectangle().fill(.quaternary)
                Image(systemName: "music.note")
                    .font(.system(size: size * 0.4))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.12, style: .continuous))
    }
}
