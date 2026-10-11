import SwiftUI

/// The app's shared objects. The CarPlay screen is a separate scene (and can start without
/// the phone screen ever opening), so it reaches the same player and library through here.
@MainActor
enum AppObjects {
    static let settings = SettingsStore()
    static let library = LibraryManager(settings: settings)
    static let songData = SongDataStore()
    static let analyzer = LibraryAnalyzer(store: songData, settings: settings)
    static let player = PlayerManager(settings: settings, songData: songData)
    static let editor = SongEditor(library: library, player: player)
}

@main
struct AudioForgeApp: App {
    @StateObject private var settings = AppObjects.settings
    @StateObject private var library = AppObjects.library
    @StateObject private var songData = AppObjects.songData
    @StateObject private var analyzer = AppObjects.analyzer
    @StateObject private var player = AppObjects.player
    @StateObject private var playlists = PlaylistManager()
    @StateObject private var editor = AppObjects.editor

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
