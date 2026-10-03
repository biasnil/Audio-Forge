import SwiftUI
import AVKit

/// Full-screen player, opened by tapping the mini player.
struct NowPlayingView: View {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var editor: SongEditor
    @Environment(\.scenePhase) private var scenePhase
    @State private var lyrics: LyricsContent = .none
    @State private var showLyrics = false
    @State private var videoFailed = false
    @State private var backdrop: (key: String, image: UIImage)?

    private let rates: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    private var lyricsMode: Bool { showLyrics && lyrics.isAvailable }

    private var wallpaperURL: URL? {
        guard let song = player.currentSong, !videoFailed else { return nil }
        return WallpaperFiles.resolve(songKey: song.key, in: settings.settings)
    }

    var body: some View {
        VStack(spacing: 18) {
            if lyricsMode {
                HStack(spacing: 12) {
                    ArtworkView(data: player.currentSong?.artworkData, size: 56, cacheKey: player.currentSong?.key)
                    titleBlock(alignment: .leading, large: false)
                    Spacer()
                }
                .padding(.top, 28)

                lyricsPanel
                    .frame(maxHeight: .infinity)
            } else {
                Spacer(minLength: 16)
                ArtworkView(data: player.currentSong?.artworkData, size: 300, cacheKey: player.currentSong?.key,
                            cornerRadius: 10)
                    .shadow(color: .black.opacity(0.3), radius: 20, y: 10)
                    .contextMenu { coverMenu }                  // long-press the cover
                titleBlock(alignment: .center, large: true)
            }

            ProgressSlider(remaining: true, timeFont: .caption.monospacedDigit())
            controls
            volume
            extras

            if !lyricsMode { Spacer(minLength: 8) }
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 12)
        .background { background }
        .presentationDragIndicator(.visible)
        .task(id: lyricsLookupID) {
            guard let song = player.currentSong else {
                lyrics = .none
                return
            }
            lyrics = .loading
            let found = await LyricsFinder.find(for: song)
            if !Task.isCancelled { lyrics = found }
        }
        .onChange(of: player.currentSong?.key) { _, _ in videoFailed = false }
        .task(id: player.currentSong?.key) {
            guard let song = player.currentSong, let data = song.artworkData else {
                backdrop = nil
                return
            }
            let image = await Task.detached(priority: .utility) { ImageTools.backdrop(data) }.value
            if let image, !Task.isCancelled { backdrop = (song.key, image) }
        }
    }

    /// Title/artist too: after a tag edit the lookup runs again with the new names.
    private var lyricsLookupID: String {
        guard let song = player.currentSong else { return "" }
        return "\(song.key)|\(song.title)|\(song.artist)"
    }

    // MARK: - Pieces

    @ViewBuilder
    private var background: some View {
        if let url = wallpaperURL {
            VideoWallpaperView(url: url, isActive: scenePhase == .active)
                .opacity(Double(settings.settings.videoWallpaperOpacityPercent) / 100)
                .overlay(Color(.systemBackground).opacity(0.25))
                .ignoresSafeArea()
                .task(id: url) {
                    // A deleted or unplayable video falls back to the blurred cover.
                    let playable = (try? await AVURLAsset(url: url).load(.isPlayable)) ?? false
                    if !playable { videoFailed = true }
                }
        } else if let backdrop, backdrop.key == player.currentSong?.key {
            // Blurred once per song, in the background (see backdropTask).
            Image(uiImage: backdrop.image)
                .resizable()
                .scaledToFill()
                .opacity(0.35)
                .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private var coverMenu: some View {
        if let song = player.currentSong {
            Button("Edit Tags", systemImage: "tag") { editor.editing = song }
            Button("Change Cover", systemImage: "photo") { editor.changeCover(song) }
        }
    }

    @ViewBuilder
    private var lyricsPanel: some View {
        switch lyrics {
        case .synced(let lines): LyricsView(lines: lines)
        case .plain(let lines): PlainLyricsView(lines: lines)
        default: EmptyView()
        }
    }

    private func titleBlock(alignment: HorizontalAlignment, large: Bool) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            Text(player.currentSong?.title ?? "Not Playing")
                .font(large ? .title2.bold() : .headline)
                .lineLimit(1)
            Text(player.currentSong?.artist ?? "")
                .font(large ? .body : .subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private var controls: some View {
        HStack {
            Button { player.cycleShuffle() } label: {
                VStack(spacing: 2) {
                    Image(systemName: "shuffle")
                    Text(shuffleLabel).font(.system(size: 9, weight: .semibold))
                }
                .foregroundStyle(player.shuffleMode == .off ? Color.secondary : Color.accentColor)
            }
            .accessibilityLabel("Shuffle: \(shuffleLabel)")
            Spacer()
            Button { player.previous() } label: {
                Image(systemName: "backward.fill").font(.title)
            }
            Spacer()
            Button { player.togglePlayPause() } label: {
                Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 68))
            }
            Spacer()
            Button { player.next() } label: {
                Image(systemName: "forward.fill").font(.title)
            }
            Spacer()
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.repeatMode.iconName)
                    .foregroundStyle(player.repeatMode == .off ? Color.secondary : Color.accentColor)
            }
        }
        .font(.title3)
        .buttonStyle(.plain)
    }

    private var shuffleLabel: String {
        switch player.shuffleMode {
        case .off: "Off"
        case .random: "Random"
        case .smart: "Smart"
        }
    }

    /// The app's own volume (up to 200%) and the AirPlay / Bluetooth output picker.
    private var volume: some View {
        HStack(spacing: 10) {
            Image(systemName: "speaker.fill").font(.caption).foregroundStyle(.secondary)
            Slider(
                value: Binding(get: { Double(player.volumePercent) },
                               set: { player.setVolume(Int($0.rounded())) }),
                in: 0...Double(AppSettings.maxVolumePercent),
                onEditingChanged: { editing in if !editing { player.saveVolume() } }
            )
            .tint(player.volumePercent > 100 ? Color.orange : Color.accentColor)
            Text("\(player.volumePercent)%")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 40, alignment: .trailing)
            RoutePicker()
                .frame(width: 28, height: 28)
        }
    }

    /// Speed, lyrics toggle, sleep timer.
    private var extras: some View {
        HStack {
            Menu {
                Picker("Playback Speed",
                       selection: Binding(get: { player.rate }, set: { player.setRate($0) })) {
                    ForEach(rates, id: \.self) { rate in
                        Text(rateText(rate)).tag(rate)
                    }
                }
            } label: {
                Text(rateText(player.rate))
                    .font(.subheadline.bold())
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.gray.opacity(0.2), in: Capsule())
            }

            Spacer()

            Button {
                withAnimation { showLyrics.toggle() }
            } label: {
                if lyrics == .loading {
                    ProgressView()
                } else {
                    Image(systemName: lyricsMode ? "quote.bubble.fill" : "quote.bubble")
                        .foregroundStyle(lyricsMode ? Color.accentColor : Color.primary)
                }
            }
            .disabled(!lyrics.isAvailable)
            .opacity(lyrics.isAvailable || lyrics == .loading ? 1 : 0.3)
            .accessibilityLabel(lyrics == .notFound ? "No lyrics found" : "Lyrics")

            Spacer()

            Menu {
                Picker("Sleep Timer",
                       selection: Binding(get: { player.sleepTimer }, set: { player.setSleepTimer($0) })) {
                    Text("Off").tag(SleepTimer.off)
                    ForEach([15, 30, 45, 60], id: \.self) { minutes in
                        Text("\(minutes) minutes").tag(SleepTimer.minutes(minutes))
                    }
                    Text("End of Current Song").tag(SleepTimer.endOfTrack)
                }
            } label: {
                sleepLabel
            }
        }
        .font(.title3)
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var sleepLabel: some View {
        if let end = player.sleepEndDate {
            HStack(spacing: 4) {
                Image(systemName: "moon.zzz.fill")
                Text(end, style: .timer)
                    .font(.subheadline.monospacedDigit())
            }
            .foregroundStyle(Color.accentColor)
        } else {
            Image(systemName: player.sleepTimer == .off ? "moon.zzz" : "moon.zzz.fill")
                .foregroundStyle(player.sleepTimer == .off ? Color.primary : Color.accentColor)
        }
    }

    private func rateText(_ rate: Float) -> String {
        String(format: "%g×", Double(rate))
    }
}

/// AirPlay / Bluetooth output picker (only shows routes on a real iPhone).
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
