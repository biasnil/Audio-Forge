import SwiftUI

/// Library stats, appearance, playback, wallpaper, lyrics and visible tabs.
struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var library: LibraryManager
    @State private var musixmatchKey = ""
    @State private var savedKey = ""

    private var s: AppSettings { settings.settings }

    var body: some View {
        let stats = LibraryStats(library.songs)

        NavigationStack {
            Form {
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
                } header: {
                    Text("Library")
                } footer: {
                    Text("Long-press a song to edit its tags or change its cover.")
                }

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
                } header: {
                    Text("Playback")
                } footer: {
                    Text("ReplayGain evens out loudness between songs using the gain stored in their tags.")
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

    private func binding<Value>(_ keyPath: WritableKeyPath<AppSettings, Value>) -> Binding<Value> {
        Binding(get: { settings.settings[keyPath: keyPath] },
                set: { value in settings.update { $0[keyPath: keyPath] = value } })
    }
}
