import SwiftUI
import AVKit
import PhotosUI

/// Full-screen player, opened by tapping the mini player.
struct NowPlayingView: View {
    @EnvironmentObject private var player: PlayerManager
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var editor: SongEditor
    @EnvironmentObject private var songData: SongDataStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var lyrics: LyricsContent = .none
    @State private var showLyrics = false
    @State private var videoFailed = false
    @State private var backdrop: (key: String, image: UIImage)?
    // Now Playing is itself a sheet, so the root view can't show the tag editor or photo
    // picker on top of it: they're presented from here instead.
    @State private var editingSong: Song?
    @State private var coverSong: Song?
    @State private var showCoverPicker = false
    @State private var coverItem: PhotosPickerItem?
    @State private var showUpNext = false
    @State private var showFullLyrics = false
    @State private var showVisualizer = false
    /// Tint taken from the cover (per artwork id).
    @State private var theme: (key: String, color: Color)?

    private let rates: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    private var lyricsMode: Bool { showLyrics && lyrics.isAvailable }

    private var wallpaperURL: URL? {
        guard let song = player.currentSong, !videoFailed else { return nil }
        return WallpaperFiles.resolve(songKey: song.key, in: settings.settings)
    }

    private var themeColor: Color? {
        guard let theme, theme.key == player.currentSong?.artworkID else { return nil }
        return theme.color
    }

    var body: some View {
        VStack(spacing: 16) {
            topBar
            if lyricsMode {
                HStack(spacing: 12) {
                    ArtworkView(artworkID: player.currentSong?.artworkID, size: 56)
                    titleBlock(alignment: .leading, large: false)
                    Spacer()
                }
                .padding(.top, 28)

                lyricsPanel
                    .frame(maxHeight: .infinity)
                    .onTapGesture(count: 2) { showFullLyrics = true }
            } else {
                Spacer(minLength: 8)
                Group {
                    if showVisualizer {
                        VisualizerView(data: player.visualizer)
                            .frame(width: 300, height: 300)
                    } else {
                        ArtworkView(artworkID: player.currentSong?.artworkID, size: 300, cornerRadius: 10)
                            .shadow(color: (themeColor ?? .black).opacity(0.4), radius: 24, y: 10)
                            .contextMenu { coverMenu }              // long-press the cover
                    }
                }
                titleBlock(alignment: .center, large: true)
            }

            ProgressSlider(remaining: true, timeFont: .caption.monospacedDigit())
            if player.isLongTrack { skipButtons }
            controls
            volume
            extras

            if !lyricsMode { Spacer(minLength: 8) }
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 12)
        .tint(themeColor)
        .background { background }
        .presentationDragIndicator(.visible)
        .sheet(isPresented: $showUpNext) {
            UpNextView()
        }
        .fullScreenCover(isPresented: $showFullLyrics) {
            if let song = player.currentSong {
                FullScreenLyricsView(song: song, content: lyrics)
            }
        }
        .onChange(of: showVisualizer) { _, on in player.setVisualizerActive(on && scenePhase == .active) }
        .onChange(of: scenePhase) { _, phase in player.setVisualizerActive(showVisualizer && phase == .active) }
        .onDisappear { player.setVisualizerActive(false) }
        .sheet(item: $editingSong) { song in
            TagEditorView(song: song)
        }
        .photosPicker(isPresented: $showCoverPicker, selection: $coverItem, matching: .images)
        .onChange(of: coverItem) { _, item in
            guard let item else { return }
            Task {
                await editor.applyCover(item, to: coverSong)
                coverSong = nil
                coverItem = nil
            }
        }
        .modifier(PlaybackMessages())
        .task(id: lyricsLookupID) {
            guard let song = player.currentSong else {
                lyrics = .none
                return
            }
            lyrics = .loading
            let found = await Task.detached(priority: .userInitiated) { await LyricsFinder.find(for: song) }.value
            if !Task.isCancelled { lyrics = found }
        }
        .onChange(of: player.currentSong?.key) { _, _ in videoFailed = false }
        .task(id: player.currentSong?.artworkID) {
            guard let artworkID = player.currentSong?.artworkID else {
                backdrop = nil
                theme = nil
                return
            }
            let result = await Task.detached(priority: .utility) { () -> (UIImage?, UIColor?) in
                guard let data = ArtworkStore.load(artworkID) else { return (nil, nil) }
                return (ImageTools.backdrop(data), ImageTools.themeColor(data))
            }.value
            guard !Task.isCancelled else { return }
            if let image = result.0 { backdrop = (artworkID, image) }
            theme = result.1.map { (artworkID, Color(uiColor: $0)) }
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
        } else if let backdrop, backdrop.key == player.currentSong?.artworkID {
            // Blurred once per song, in the background; tinted with the cover's colour.
            ZStack {
                Image(uiImage: backdrop.image)
                    .resizable()
                    .scaledToFill()
                    .opacity(0.35)
                if let themeColor {
                    LinearGradient(colors: [themeColor.opacity(0.35), .clear],
                                   startPoint: .top, endPoint: .bottom)
                }
            }
            .ignoresSafeArea()
        }
    }

    /// Love (left) and Up Next (right).
    private var topBar: some View {
        HStack {
            if let song = player.currentSong {
                let loved = songData.isFavorite(song.key)
                Button {
                    songData.toggleFavorite(song.key)
                } label: {
                    Image(systemName: loved ? "heart.fill" : "heart")
                        .foregroundStyle(loved ? Color.pink : Color.secondary)
                }
                .accessibilityLabel(loved ? "Unlove" : "Love")
            }
            Spacer()
            if lyricsMode {
                Button { showFullLyrics = true } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .accessibilityLabel("Full-screen lyrics")
            }
            Button { showUpNext = true } label: {
                Image(systemName: "list.bullet")
            }
            .accessibilityLabel("Up Next")
        }
        .font(.title3)
        .buttonStyle(.plain)
        .padding(.top, 20)
    }

    /// ±15 s for long tracks (audiobooks, mixes).
    private var skipButtons: some View {
        HStack(spacing: 48) {
            Button { player.skip(by: -15) } label: { Image(systemName: "gobackward.15") }
                .accessibilityLabel("Back 15 seconds")
            Button { player.skip(by: 15) } label: { Image(systemName: "goforward.15") }
                .accessibilityLabel("Forward 15 seconds")
        }
        .font(.title2)
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var coverMenu: some View {
        if let song = player.currentSong {
            Button("Edit Tags", systemImage: "tag") { editingSong = song }
            Button("Change Cover", systemImage: "photo") {
                coverSong = song
                showCoverPicker = true
            }
        }
    }

    @ViewBuilder
    private var lyricsPanel: some View {
        switch lyrics {
        case .synced(let lines):
            LyricsView(lines: lines, offset: player.currentSong.map { songData.stats(for: $0.key).lyricsOffset ?? 0 } ?? 0)
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

    /// Speed, lyrics, visualizer, A–B repeat, sleep timer.
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

            Button {
                withAnimation { showVisualizer.toggle() }
            } label: {
                Image(systemName: "waveform")
                    .foregroundStyle(showVisualizer ? Color.accentColor : Color.primary)
            }
            .disabled(lyricsMode)
            .accessibilityLabel("Visualizer")

            Spacer()

            Button { player.cycleABRepeat() } label: {
                abLabel
            }
            .accessibilityLabel("A-B repeat")

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
    private var abLabel: some View {
        switch player.abRepeat {
        case .off:
            Text("A–B").font(.subheadline.bold())
        case .aSet:
            Text("A–").font(.subheadline.bold()).foregroundStyle(Color.accentColor)
        case .looping:
            Text("A–B").font(.subheadline.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .background(Color.accentColor, in: Capsule())
        }
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
