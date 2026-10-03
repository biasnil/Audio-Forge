import SwiftUI

@main
struct MP3PlayerApp: App {
    @StateObject private var library = LibraryManager()
    @StateObject private var player = PlayerManager()
    @StateObject private var playlists = PlaylistManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
                .environmentObject(player)
                .environmentObject(playlists)
        }
    }
}
