# AudioForge for iOS (MP3Player, SwiftUI)

Offline music player for iPhone. Swift + SwiftUI + AVFoundation, iOS 17+, built in Xcode.
It matches the Android app's features (see the root README), plus playback speed,
a sleep timer and resuming the last song on launch.

## Files
- MP3PlayerApp.swift    – @main entry; creates SettingsStore, LibraryManager, PlayerManager,
                          PlaylistManager and SongEditor
- Song.swift            – Song model (key, tags, duration, bitrate, artwork, lyrics file, ReplayGain)
- SettingsStore.swift   – AppSettings (JSON in Application Support), tabs, linked folders,
                          wallpaper assignments; Keychain helper for the Musixmatch key
- LibraryManager.swift  – Scans Documents (subfolders included) and linked folders, reads tags,
                          matches .lrc/.txt lyrics and cover.jpg/folder.jpg; skips unchanged files
- TagIO.swift           – Reads/writes tags: ID3v2 (MP3) and FLAC by hand, M4A via AVFoundation
- PlaybackQueue.swift   – Queue, shuffle (Off / Random / Smart), repeat and play history
                          (port of the desktop/Android PlaybackQueue)
- PlayerManager.swift   – AVAudioEngine playback: two player slots for crossfade, 10-band EQ,
                          ReplayGain, volume up to 200%, speed, sleep timer, interruptions,
                          headphone unplug, lock screen controls, resume on launch
- Equalizer.swift       – EQ bands, presets, dB helpers
- PlaylistManager.swift – Playlists saved as JSON in Application Support (songs stored by key)
- ContentView.swift     – Tabs (hideable), Songs tab (search/sort), mini player, alerts
- BrowseViews.swift     – Albums grid, Artists list, album/artist/folder song lists
- FoldersView.swift     – Folders with music; link/unlink folders from the Files app
- PlaylistViews.swift   – Playlists list, playlist detail (reorder/remove, unavailable songs), song picker
- TagEditorView.swift   – Long-press menu (Edit Tags / Change Cover), tag editor sheet
- EqualizerView.swift   – EQ on/off, presets, 10 bands, post-gain
- Wallpapers.swift      – Video wallpapers: global + per-song videos, looping muted player
- SettingsView.swift    – Library stats, theme, ReplayGain, crossfade, wallpaper, Musixmatch key, tabs
- NowPlayingView.swift  – Full-screen player (controls, volume/AirPlay, speed, lyrics, sleep timer)
- LyricsView.swift      – .lrc parser (UTF-8/UTF-16/GB18030), synced and plain lyrics views
- LyricsFinder.swift    – Lyrics lookup: cache, LRCLIB, Musixmatch, then the local .lrc/.txt
- Artwork.swift         – PlaybackClock (playback position), thumbnail cache, ArtworkView,
                          background image downsampling and the Now Playing backdrop blur
- CarPlay.swift         – CarPlay scene: menu, Lyrics button on Now Playing, lyrics list

## Xcode project setup
1. iOS App template, SwiftUI, Swift.
2. **iPhone only.** General → Supported Destinations: remove **Mac** (select it, click −).
   If the project was created as Multiplatform, also delete **App Sandbox** in Signing &
   Capabilities (trash icon on its row) — it's macOS-only, and while the target is a Mac
   target the iPhone settings below don't show up.
3. Add every .swift file in this folder to the app target. (With Xcode 16+'s folder-synced
   groups, files dropped into the group are added automatically.)
4. App icon: use the Assets.xcassets folder from here (or copy its AppIcon.appiconset over
   the one in your project's Assets). It's a single 1024×1024 image made from the Android
   launcher icon; Xcode makes the other sizes.
5. **Background audio (required)** — without it music stops when you leave the app or lock
   the phone, and the next song never starts. Signing & Capabilities → **+ Capability** →
   **Background Modes** → tick **Audio, AirPlay, and Picture in Picture**. (This adds
   `UIBackgroundModes = audio` to Info.plist.) The app shows a "Background Audio Is Off"
   alert at launch, and a warning in Settings, until this is on.
6. **Show songs in the Files app** — Info tab, hover a row, click **+**, and add (both YES):
   - Application supports iTunes file sharing (`UIFileSharingEnabled`)
   - Supports opening documents in place (`LSSupportsOpeningDocumentsInPlace`)

   If the Info tab won't add them, set them in Build Settings instead: search
   `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace`, set both to Yes.
7. Rebuild and run on the iPhone (delete the old app first if the icon doesn't update).
8. The project uses default MainActor isolation (Xcode 26 default), so background-safe
   types are marked `nonisolated`. `import Combine` is required where @Published is used.

No permission prompts are needed: photos and videos are picked with the system picker,
folders with the Files picker, and lyrics lookups work with the default network settings.

## Lyrics in the car
**Lock screen / CarPlay line (no setup).** Settings → Lyrics → **Lyrics on Lock Screen &
CarPlay** shows the line being sung (synced lyrics only) in place of the album name on the
lock screen, Control Center and CarPlay's Now Playing screen.

**CarPlay Lyrics screen (needs setup).** Adds AudioForge to the CarPlay home screen with a
menu (Now Playing, Lyrics) and a Lyrics button on Now Playing. Without these steps
CarPlay.swift is simply never used.
1. **Entitlement.** Apple must approve CarPlay for your developer account: request the
   CarPlay **audio** entitlement at developer.apple.com/contact/carplay (a paid developer
   account is required; free personal teams can't get it). Once approved, add a file
   `MP3Player.entitlements` to the target (or edit the existing one) with:
   ```xml
   <key>com.apple.developer.carplay-audio</key>
   <true/>
   ```
   and make sure Build Settings → **Code Signing Entitlements** points to it. The Simulator
   accepts the entitlement without approval, so you can try it there first.
2. **CarPlay scene.** Info tab → add **Application Scene Manifest**
   (`UIApplicationSceneManifest`), or in Info.plist as source code:
   ```xml
   <key>UIApplicationSceneManifest</key>
   <dict>
       <key>UIApplicationSupportsMultipleScenes</key>
       <true/>
       <key>UISceneConfigurations</key>
       <dict>
           <key>CPTemplateApplicationSceneSessionRoleApplication</key>
           <array>
               <dict>
                   <key>UISceneConfigurationName</key>
                   <string>CarPlay</string>
                   <key>UISceneDelegateClassName</key>
                   <string>CarPlaySceneDelegate</string>
               </dict>
           </array>
       </dict>
   </dict>
   ```
   If the project already has a scene manifest, add only the
   `CPTemplateApplicationSceneSessionRoleApplication` entry. The phone screen stays SwiftUI's.
3. **Try it without a car:** run on the Simulator, then Simulator menu → I/O → External
   Displays → **CarPlay**.

The CarPlay list is built from list items (CarPlay has no lyrics view): the car limits how
many rows it shows and cuts each to one line. Synced lyrics start at the line being sung and
move along with the song; tap a line to jump to it. Plain lyrics show from the top.

## Performance notes
- Test speed with a **Release** build (Edit Scheme → Run → Build Configuration → Release, or
  TestFlight). Debug builds with the debugger attached are several times slower in SwiftUI.
- The playback position is published by `PlaybackClock`, not `PlayerManager`, so only the
  progress bars and lyrics redraw while music plays. Seek bars seek once, when released.
- Cover art is kept at ≤900 px; list rows decode small thumbnails in the background and
  cache them. The Now Playing backdrop is blurred once per song.
- Lists, album/artist/folder groups and stats are computed when the library changes, not on
  every redraw. Returning to the app only rescans if Documents changed or 10 minutes passed
  (pull to refresh always rescans).

## Where things are stored
- Songs you import: the app's Documents folder (visible in Files and Finder).
- Linked folders: read in place through a security-scoped bookmark (nothing is copied).
- Settings: Application Support/settings.json. Playlists: Application Support/playlists.json.
- Wallpaper videos: Application Support/Wallpapers. Cached lyrics: Caches/lyrics.
- Musixmatch API key: the Keychain.

## Phases completed
1. Core playback, import, background audio, lock screen
2. Full-screen player, shuffle/repeat, call interruptions, headphone unplug
3. Playlists
4. Search, sort, Albums and Artists
5. Synced .lrc lyrics (same file name as the song)
6. Playback speed, sleep timer, resume last song on launch
7. Parity with Android:
   - AVAudioEngine: crossfade (2–15 s), 10-band EQ with presets and post-gain, ReplayGain,
     volume up to 200%
   - Shuffle Off / Random / Smart, and Previous follows play history
   - Tag editor and cover changes, written into MP3, FLAC and M4A files
   - Lyrics lookup: LRCLIB, Musixmatch, plain lyrics and a cache
   - Folders: subfolders, linked folders, cover.jpg covers
   - Video wallpapers
   - Settings tab, theme, hideable tabs
   - More tags, sort by Album/Year, unavailable playlist songs, playback error alerts
