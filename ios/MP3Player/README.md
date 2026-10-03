# MP3Player (iOS, SwiftUI)

Offline MP3 player for iPhone. Swift + SwiftUI + AVFoundation, iOS 17+, built in Xcode.

## Files
- MP3PlayerApp.swift   – @main entry, creates LibraryManager, PlayerManager, PlaylistManager
- Song.swift           – Song model (url, title, artist, album, artwork, dateAdded, lyricsURL)
- LibraryManager.swift – Scans Documents folder, imports files, reads ID3 tags, matches .lrc lyrics
- PlayerManager.swift  – AVAudioPlayer playback, queue, shuffle/repeat, speed, sleep timer,
                         interruptions, headphone unplug, lock screen controls, resume on launch
- PlaylistManager.swift – Playlists saved as JSON in Application Support (songs stored by file name)
- ContentView.swift    – TabView (Songs/Albums/Artists/Playlists), Songs tab (search/sort), mini player
- BrowseViews.swift    – Albums grid, Artists list, album/artist song lists
- PlaylistViews.swift  – Playlists list, playlist detail (reorder/remove), song picker
- NowPlayingView.swift – Full-screen player (controls, volume/AirPlay, speed, lyrics, sleep timer)
- LyricsView.swift     – .lrc parser (UTF-8/UTF-16/GB18030) + auto-scrolling synced lyrics view

## Xcode project setup
1. iOS App template, SwiftUI, Swift. Supported Destinations: iPhone only (not Mac).
2. Signing & Capabilities: Background Modes -> "Audio, AirPlay, and Picture in Picture".
3. Info tab keys (both YES):
   - UIFileSharingEnabled (Application supports iTunes file sharing)
   - LSSupportsOpeningDocumentsInPlace (Supports opening documents in place)
4. The project uses explicit module imports (import Combine is required where @Published is used).

## Phases completed
1. Core playback, import, background audio, lock screen
2. Full-screen player, shuffle/repeat, call interruptions, headphone unplug
3. Playlists
4. Search, sort, Albums and Artists
5. Synced .lrc lyrics (same file name as the song)
6. Playback speed, sleep timer, resume last song on launch
