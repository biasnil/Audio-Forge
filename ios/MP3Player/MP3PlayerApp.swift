import SwiftUI

@main
struct MP3PlayerApp: App {
    @StateObject private var settings: SettingsStore
    @StateObject private var library: LibraryManager
    @StateObject private var songData: SongDataStore
    @StateObject private var analyzer: LibraryAnalyzer
    @StateObject private var player: PlayerManager
    @StateObject private var playlists = PlaylistManager()
    @StateObject private var editor: SongEditor

    init() {
        let settings = SettingsStore()
        let library = LibraryManager(settings: settings)
        let songData = SongDataStore()
        let player = PlayerManager(settings: settings, songData: songData)
        _settings = StateObject(wrappedValue: settings)
        _library = StateObject(wrappedValue: library)
        _songData = StateObject(wrappedValue: songData)
        _analyzer = StateObject(wrappedValue: LibraryAnalyzer(store: songData, settings: settings))
        _player = StateObject(wrappedValue: player)
        _editor = StateObject(wrappedValue: SongEditor(library: library, player: player))
    }

    var body: some Scene {
        WindowGroup {
            ContentView(settings: settings, library: library, player: player, songData: songData)
                .environmentObject(settings)
                .environmentObject(library)
                .environmentObject(songData)
                .environmentObject(analyzer)
                .environmentObject(player)
                .environmentObject(player.clock)
                .environmentObject(playlists)
                .environmentObject(editor)
        }
    }
}
