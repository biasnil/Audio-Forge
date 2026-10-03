import SwiftUI
import MediaPlayer

/// Full-screen player, opened by tapping the mini player.
struct NowPlayingView: View {
    @EnvironmentObject private var player: PlayerManager
    @State private var lyrics: [LyricLine] = []
    @State private var showLyrics = false

    private let rates: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    private var lyricsMode: Bool { showLyrics && !lyrics.isEmpty }

    var body: some View {
        VStack(spacing: 20) {
            if lyricsMode {
                HStack(spacing: 12) {
                    ArtworkView(data: player.currentSong?.artworkData, size: 56)
                    titleBlock(alignment: .leading, large: false)
                    Spacer()
                }
                .padding(.top, 28)

                LyricsView(lines: lyrics)
                    .frame(maxHeight: .infinity)
            } else {
                Spacer(minLength: 16)
                ArtworkView(data: player.currentSong?.artworkData, size: 320)
                    .shadow(color: .black.opacity(0.3), radius: 20, y: 10)
                titleBlock(alignment: .center, large: true)
            }

            progress
            controls

            // System volume + AirPlay button (only shows on a real iPhone).
            VolumeSlider()
                .frame(height: 36)

            extras

            if !lyricsMode { Spacer(minLength: 8) }
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 12)
        .background {
            if let data = player.currentSong?.artworkData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 60)
                    .opacity(0.35)
                    .ignoresSafeArea()
            }
        }
        .presentationDragIndicator(.visible)
        .task(id: player.currentSong?.id) {
            lyrics = player.currentSong?.lyricsURL.map { LRCParser.load(from: $0) } ?? []
        }
    }

    // MARK: - Pieces

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

    private var progress: some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(get: { player.currentTime }, set: { player.seek(to: $0) }),
                in: 0...max(player.duration, 1)
            )
            HStack {
                Text(format(player.currentTime))
                Spacer()
                Text("-" + format(max(player.duration - player.currentTime, 0)))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private var controls: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                Image(systemName: "shuffle")
                    .foregroundStyle(player.isShuffled ? Color.accentColor : Color.secondary)
            }
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
                Image(systemName: lyricsMode ? "quote.bubble.fill" : "quote.bubble")
                    .foregroundStyle(lyricsMode ? Color.accentColor : Color.primary)
            }
            .disabled(lyrics.isEmpty)
            .opacity(lyrics.isEmpty ? 0.3 : 1)

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

    private func format(_ time: TimeInterval) -> String {
        let seconds = Int(time)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

struct VolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        MPVolumeView(frame: .zero)
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}
