import SwiftUI

@main
struct MP3PlayerApp: App {
    @StateObject private var settings: SettingsStore
    @StateObject private var library: LibraryManager
    @StateObject private var player: PlayerManager
    @StateObject private var playlists = PlaylistManager()
    @StateObject private var editor: SongEditor

    init() {
        let settings = SettingsStore()
        let library = LibraryManager(settings: settings)
        let player = PlayerManager(settings: settings)
        _settings = StateObject(wrappedValue: settings)
        _library = StateObject(wrappedValue: library)
        _player = StateObject(wrappedValue: player)
        _editor = StateObject(wrappedValue: SongEditor(library: library, player: player))
    }

    var body: some Scene {
        WindowGroup {
            ContentView(settings: settings, library: library, player: player)
                .environmentObject(settings)
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(player.clock)
                .environmentObject(playlists)
                .environmentObject(editor)
        }
    }
}
