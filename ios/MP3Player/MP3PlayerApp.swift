import SwiftUI

/// The app's shared objects. The CarPlay screen is a separate scene (and can start without
/// the phone screen ever opening), so it reaches the same player and library through here.
@MainActor
enum AppObjects {
    static let settings = SettingsStore()
    static let library = LibraryManager(settings: settings)
    static let player = PlayerManager(settings: settings)
    static let editor = SongEditor(library: library, player: player)
}

@main
struct MP3PlayerApp: App {
    @StateObject private var settings = AppObjects.settings
    @StateObject private var library = AppObjects.library
    @StateObject private var player = AppObjects.player
    @StateObject private var playlists = PlaylistManager()
    @StateObject private var editor = AppObjects.editor

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(settings)
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(player.clock)
                .environmentObject(playlists)
                .environmentObject(editor)
        }
    }
}
