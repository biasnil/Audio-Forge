import SwiftUI

/// Library stats, appearance, playback, wallpaper, lyrics and visible tabs.
struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var songData: SongDataStore
    @EnvironmentObject private var analyzer: LibraryAnalyzer
    @State private var musixmatchKey = ""
    @State private var savedKey = ""

    private var s: AppSettings { settings.settings }

    var body: some View {
        let stats = library.stats

        TabStack {
            Form {
                if !BackgroundAudio.isEnabled {
                    Section {
                        Label("Background audio is off", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text("Music stops when you leave the app. In Xcode: target → Signing & Capabilities → "
                             + "+ Capability → Background Modes → tick \"Audio, AirPlay, and Picture in Picture\".")
                            .font(.footnote)
                    }
                }
                Section {
                    LabeledContent("Songs", value: "\(stats.songs)")
                    LabeledContent("Albums", value: "\(stats.albums)")
                    LabeledContent("Artists", value: "\(stats.artists)")
                    LabeledContent("Genres", value: "\(stats.genres)")
                    Button {
                        Task { await library.reload() }
                    } label: {
                        HStack {
                            Text("Refresh Library")
                            if library.isScanning {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(library.isScanning)
                    analysisRow
                    NavigationLink("Find Duplicates") { DuplicatesView() }
                } header: {
                    Text("Library")
                } footer: {
                    Text("Analysis finds each song's tempo (BPM), key, and where its sound starts and ends, "
                         + "on this iPhone (nothing is uploaded). It unlocks Smart Options. "
                         + "Long-press a song to edit its tags or change its cover.")
                }

                smartOptions

                Section("Appearance") {
                    Picker("Theme", selection: binding(\.appearance)) {
                        ForEach(Appearance.allCases) { Text($0.rawValue).tag($0) }
                    }
                }

                Section {
                    Toggle("ReplayGain", isOn: binding(\.replayGainEnabled))
                    Toggle("Crossfade", isOn: binding(\.crossfadeEnabled))
                    VStack(alignment: .leading) {
                        Text("Crossfade length: \(s.crossfadeSeconds) s")
                        Slider(value: Binding(
                            get: { Double(s.crossfadeSeconds) },
                            set: { value in settings.update { $0.crossfadeSeconds = Int(value.rounded()) } }),
                               in: Double(AppSettings.crossfadeRange.lowerBound)...Double(AppSettings.crossfadeRange.upperBound),
                               step: 1)
                    }
                    .disabled(!s.crossfadeEnabled)
                    Toggle("Fade Out Sleep Timer", isOn: binding(\.sleepFadeOut))
                    Toggle("Resume Long Tracks", isOn: binding(\.rememberLongTrackPosition))
                } header: {
                    Text("Playback")
                } footer: {
                    Text("ReplayGain evens out loudness between songs using the gain stored in their tags. "
                         + "With crossfade off, songs play gapless. Resume Long Tracks: audiobooks and mixes "
                         + "(10 minutes or longer) continue where you stopped.")
                }

                Section("Video Wallpaper") {
                    Toggle("Show Video Wallpapers", isOn: binding(\.videoWallpaperEnabled))
                    VStack(alignment: .leading) {
                        Text("Opacity: \(s.videoWallpaperOpacityPercent)%")
                        Slider(value: Binding(
                            get: { Double(s.videoWallpaperOpacityPercent) },
                            set: { value in settings.update { $0.videoWallpaperOpacityPercent = Int(value.rounded()) } }),
                               in: 0...100, step: 1)
                    }
                    .disabled(!s.videoWallpaperEnabled)
                }

                Section {
                    SecureField("Musixmatch API key", text: $musixmatchKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Save") {
                            let key = musixmatchKey.trimmingCharacters(in: .whitespaces)
                            Keychain.write(key, for: LyricsFinder.musixmatchKeyAccount)
                            savedKey = key
                        }
                        .disabled(musixmatchKey.trimmingCharacters(in: .whitespaces) == savedKey)
                        if !savedKey.isEmpty {
                            Spacer()
                            Button("Clear", role: .destructive) {
                                Keychain.write("", for: LyricsFinder.musixmatchKeyAccount)
                                savedKey = ""
                                musixmatchKey = ""
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                } header: {
                    Text("Lyrics")
                } footer: {
                    Text("Lyrics are looked up on LRCLIB (free, no key), then Musixmatch if you add an API key "
                         + "(stored in the Keychain), then a same-name .lrc or .txt file next to the song.")
                }

                Section("Visible Tabs") {
                    ForEach(AppTab.allCases.filter(\.hideable)) { tab in
                        Toggle(isOn: Binding(
                            get: { !s.hiddenTabs.contains(tab) },
                            set: { visible in
                                settings.update {
                                    if visible { $0.hiddenTabs.remove(tab) } else { $0.hiddenTabs.insert(tab) }
                                }
                            })) {
                            Label(tab.rawValue, systemImage: tab.systemImage)
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                savedKey = Keychain.read(LyricsFinder.musixmatchKeyAccount)
                musixmatchKey = savedKey
            }
        }
    }

    // MARK: - Analysis

    @ViewBuilder
    private var analysisRow: some View {
        let analyzed = songData.analyzedCount(of: library.songs)
        let total = library.songs.count
        if let progress = analyzer.progress {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Analyzing… \(progress.done) of \(progress.total)")
                    Spacer()
                    Button("Stop", role: .cancel) { analyzer.cancel() }
                        .buttonStyle(.borderless)
                }
                ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                Text("You can keep using the app. Analysis pauses while the app is in the background.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else {
            Button {
                analyzer.start(library.songs)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Analyze Your Songs' Key and BPM")
                    Text(analyzed == total ? "All \(total) songs analyzed"
                         : "\(analyzed) of \(total) songs analyzed")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(total == 0 || library.isScanning)
        }
    }

    // MARK: - Smart Options

    private var smartLocked: Bool { !s.analysisCompleted }

    @ViewBuilder
    private var smartOptions: some View {
        Section {
            if smartLocked {
                Label("Run \"Analyze Your Songs' Key and BPM\" (above) to unlock these.",
                      systemImage: "lock.fill")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Toggle(isOn: binding(\.smartTransitions)) {
                Label("Smart Transitions", systemImage: smartLocked ? "lock" : "wand.and.stars")
            }
            .disabled(smartLocked)
            Toggle(isOn: binding(\.beatMatchedCrossfade)) {
                Label("Beat-Matched Crossfades", systemImage: smartLocked ? "lock" : "metronome")
            }
            .disabled(smartLocked || !s.crossfadeEnabled)
            Toggle(isOn: binding(\.harmonicShuffle)) {
                Label("Harmonic Shuffle", systemImage: smartLocked ? "lock" : "dial.medium")
            }
            .disabled(smartLocked)
        } header: {
            Text("Smart Options")
        } footer: {
            Text("Smart Transitions: songs from the same album play gapless, crossfades start where a "
                 + "song's ending actually fades out, and silence at the start and end is skipped. "
                 + "Beat-Matched Crossfades (needs Crossfade on): the next song's tempo is matched and its "
                 + "beats lined up during the fade, then it eases back to normal speed. Harmonic Shuffle: "
                 + "Smart shuffle plays songs in compatible keys and similar tempos next to each other. "
                 + "Songs that haven't been analyzed, or have no steady beat, use normal transitions.")
        }
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(get: { settings.settings[keyPath: keyPath] },
                set: { value in settings.update { $0[keyPath: keyPath] = value } })
    }
}

/// Whether the app's Info.plist has the "audio" background mode (needed to keep playing
/// when the app is in the background or the phone is locked).
enum BackgroundAudio {
    static var isEnabled: Bool {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        return modes.contains("audio")
    }
}
