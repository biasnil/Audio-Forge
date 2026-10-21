# AudioForge

A Qt-based MP3 player/library manager: playback (with crossfade),
tag-based library browsing (Albums/Tracks/Artists), playlists, ReplayGain,
a 10-band equalizer, MusicBrainz and manual tag editing, cover art editing,
synced lyrics (LRCLIB / Musixmatch / local .lrc), per-track video
wallpapers, and Discord Rich Presence.

## Structure

```
audioforge/
├── CMakeLists.txt
├── README.md
├── resources/                   # app icon (.png for Qt, .ico/.rc for the Windows .exe)
├── scripts/                     # deploy_windows.sh, build_installer.sh
├── installer/                   # audioforge.iss (Inno Setup script for the Windows installer)
├── android/                     # Android app (Kotlin + Compose), see "Android app" below
├── ios/                         # iPhone app (SwiftUI), see ios/AudioForge/README.md
├── vendor/
│   └── miniaudio.h              # third-party, single-header audio library
│
├── common/                      # UI-agnostic core (Qt Core/Network; no custom widgets)
│   ├── include/audioforge/
│   │   ├── track_info.hpp       # TrackInfo / PlaylistData structs
│   │   ├── wallpaper_data.hpp   # WallpaperEntry struct
│   │   ├── audio_engine.hpp     # miniaudio wrapper (playback + crossfade + EQ)
│   │   ├── playback_queue.hpp   # shuffle / repeat / history logic
│   │   ├── discord_presence.hpp # Discord Rich Presence over local IPC
│   │   ├── secret_store.hpp     # protects stored API keys (DPAPI on Windows)
│   │   └── warnings.hpp         # ErrorReporter (warn/info/confirm + log file)
│   └── src/
│       ├── main.cpp             # entry point
│       └── ...                  # one .cpp per header above
│
└── qt_player/                   # everything Qt-widget/TagLib specific
    ├── include/audioforge/
    │   ├── player_window.hpp    # main window
    │   ├── music_library.hpp    # folders/tracks/playlists
    │   ├── wallpaper_library.hpp# video wallpaper assignments (wallpapers.json)
    │   ├── track_metadata.hpp   # TagLib read/write (basic tags, ReplayGain, cover art)
    │   ├── track_metadata_tags.hpp # TagLib read/write of every tag (manual editor)
    │   ├── cover_art_writer.hpp # replaces embedded cover art
    │   ├── lyrics_provider.hpp  # .lrc parsing, sidecar + cached lyrics
    │   ├── widgets/             # clickable / click-to-seek / video background / stacked widgets
    │   └── dialogs/             # track list, playlists, MusicBrainz, manual tags, wallpaper picker
    └── src/  (mirrors the above)
```

## Building (Windows, MSYS2 UCRT64 + VS Code)

Requires (installed via the MSYS2 UCRT64 terminal):
```
pacman -S mingw-w64-ucrt-x86_64-qt6-base mingw-w64-ucrt-x86_64-qt6-tools \
          mingw-w64-ucrt-x86_64-qt6-multimedia \
          mingw-w64-ucrt-x86_64-taglib mingw-w64-ucrt-x86_64-pkgconf
```

Then in VS Code: **CMake: Select a Kit** (the MSYS2 UCRT64 GCC kit) →
**CMake: Configure** → **CMake: Build**.

## Deploying on Windows (machines without MSYS2/Qt)

From the MSYS2 UCRT64 shell, after building:
```
scripts/deploy_windows.sh build/audioforge.exe dist
```
This produces a `dist/` folder that runs on a clean Windows install. It runs
`windeployqt6 --release`, adds the QtMultimedia FFmpeg backend
(`multimedia/ffmpegmediaplugin.dll`), writes `qt.conf` (`Plugins = .`), and then
copies every DLL that anything in the folder still loads from `/ucrt64/bin`.
It repeats that until nothing is missing.

`windeployqt6` alone isn't enough. It copies the Qt DLLs and plugins, but not
the DLLs those plugins depend on. The FFmpeg backend alone needs about 80
codec and support libraries (avcodec, libx264, libvpx, gnutls, cairo, ...).
When they're missing, the app starts but logs
`No QtMultimedia backends found` and the video wallpaper can't play.

Notes:
- On MSYS2's Qt 6 the plugin folder is `multimedia/`, not `mediaservice/`
  (the older Qt 5 name).
- To check a folder by hand, run this from inside it. It should print nothing:
  `ldd multimedia/ffmpegmediaplugin.dll | grep /ucrt64/bin/`

## Building (Linux)

Debian/Ubuntu packages:
```
sudo apt install cmake g++ pkg-config qt6-base-dev qt6-multimedia-dev libtag1-dev \
                 gstreamer1.0-plugins-base gstreamer1.0-plugins-good
cmake -B build && cmake --build build
```
The GStreamer plugins are needed at runtime by Qt Multimedia (video wallpaper).

## Android app

`android/` is a Kotlin port of the desktop app. It now has the same features as the
iOS app (see below), except the home screen widget; Android Auto takes CarPlay's place.
It was built in stages:

1. Project setup. **Done.**
2. Playback, library, queue, playlists. **Done:** the phone's music library plus
   added folders (phone-library folders can be hidden, e.g. WhatsApp audio), the Tracks/Albums/Artists/Folders/Playlists/Settings tabs,
   background playback with notification and lock-screen controls, and the
   desktop's shuffle, repeat and history logic.
3. Crossfade, equalizer, ReplayGain. **Done:** two-player crossfade (2–15 s),
   the desktop's 10-band EQ with presets and post-gain (applied in the audio
   pipeline, so it's the same on every phone), ReplayGain from MP3, FLAC,
   OGG/Opus and M4A tags, and volume up to 200%.
4. Tag and cover editing. **Done:** long-press a song (in any list, or the
   cover on Now Playing) for **Edit tags** / **Change cover**. Both are written
   into the file with jaudiotagger (MP3, FLAC, OGG Vorbis, M4A, WAV); covers
   over 1200 px are scaled down first. Android asks before changing
   phone-library songs; added folders need write access once. (The desktop's
   MusicBrainz lookup wasn't ported.)
5. Lyrics. **Done:** cache, then LRCLIB, then Musixmatch (with an API key,
   stored encrypted with the Android Keystore), then a same-name .lrc/.txt
   next to the track in an added folder; synced highlighting and tap-to-seek.
   LRCLIB is tried with several searches (exact, without "(Live)"-style
   suffixes, general search, artist/title split from the file name) and the
   result matching the song's length is preferred.
6. Video wallpapers and settings. **Done:** a global video plus per-song
   videos behind Now Playing (first matching assignment wins, like the
   desktop), muted and looping, paused in the background; on/off and opacity
   in Settings.
7. iOS parity. **Done:** the iOS tab bar (Songs, Albums, Artists, Genres,
   Folders, Playlists, Equalizer, Wallpapers, Settings, with "More" past five);
   Play next / Add to queue and an Up Next page (history, reorder, remove,
   Save as playlist), remembered across launches; gapless playback; speed,
   sleep timer (with fade-out), A–B repeat, ±15 s and resume for long tracks;
   play counts, Loved songs, smart lists and rule-based smart playlists,
   .m3u import/export, playlist covers; multi-select, import and delete, A–Z
   index, duplicate finder; full-screen word-by-word lyrics with a timing
   offset, the lyric line on the lock screen, a visualizer and cover colours
   on Now Playing; key/BPM analysis with Smart Transitions, Beat-Matched
   Crossfades and Harmonic Shuffle; Android Auto (browse the library, Lyrics
   list).

Code layout (`android/app/src/main/java/com/biasnil/audioforge/`):
```
AppContainer.kt          # app-wide objects (store, library, player, lyrics...); restores the queue
MainActivity.kt          # Compose entry point; starts PlaybackService
playback/
  PlaybackManager.kt     # two ExoPlayers: crossfade, gapless, beat-match, speed, sleep timer, A–B
  PlaybackQueue.kt       # queue, shuffle (Off/Random/Smart), repeat, history, Play next / Up Next edits
  QueueStore.kt          # queue + position saved between launches (files/queue.json)
  PlaybackService.kt     # media session (notification, lock screen) and Android Auto browsing
  QueueForwardingPlayer.kt # adds next/previous and the lyric line to the media session
audio/
  AudioEffectsProcessor.kt, Equalizer.kt # EQ + gain inside each player's audio pipeline
  AudioAnalyzer.kt, AnalysisMath.kt      # decode a song and find BPM, beats, key, silence, outro
  LibraryAnalyzer.kt     # runs the analysis over the library (background only while charging)
  Visualizer.kt          # spectrum for Now Playing
data/
  AppStore.kt            # settings + playlists (files/audioforge.json)
  LibraryRepository.kt   # the library: MediaStoreSource (phone) + FolderSource (added folders)
  Track.kt, TrackTags.kt, LibraryModel.kt # song model, tag reading, grouping/sorting/search
  SongData.kt            # play counts, Loved, long-track positions, lyrics offsets
  Analysis.kt            # analysis results, MusicKey (Camelot), harmonic order
  SmartPlaylists.kt      # smart playlist rules
  CoverArtLoader.kt, WallpaperRepository.kt, SecretBox.kt (Keystore-encrypted API key)
lyrics/                  # LRCLIB / Musixmatch / local .lrc lookup and parsing; LyricLineTracker
                         # follows the line being sung (lock screen, Android Auto)
tags/                    # tag and cover writing (jaudiotagger)
ui/                      # Compose screens: one file per tab or page; Common.kt (shared rows,
                         # buttons) and Bars.kt (top bar, tab bar, ⋮ menu) are shared by them
```

Requirements: Android 12+ (minSdk 31), targeting API 36. The app has no native
code, so it runs on both 4 KB and 16 KB memory page size devices.

To build, open the `android/` folder in Android Studio (**File → Open**), let
Gradle sync, then run the `app` configuration. It uses Kotlin, Jetpack Compose,
Gradle 8.14.3 (through the included wrapper) and Android Gradle Plugin 8.11.1.

Releasing: tag each Android release `Androidv<versionName>`, exactly matching
`versionName` in `android/app/build.gradle.kts` (for example `Androidv1.0.2`),
and attach the APK. The app's update check (Settings → About → Version) only
looks at `Androidv*` releases, so desktop releases don't affect it.

## iOS app

`ios/AudioForge/` is a SwiftUI app for iPhone (iOS 17+) with the same features as
the Android app. Playback runs on AVAudioEngine, which gives crossfade, the 10-band EQ
with presets and post-gain, ReplayGain and volume up to 200%. The app also has
Off/Random/Smart shuffle with play history, a tag and cover editor (MP3, FLAC, M4A),
lyrics from LRCLIB and Musixmatch with plain lyrics and a cache, and folders linked
from the Files app (subfolders included). The rest of the Android features are there
too: video wallpapers, a Settings tab and hideable tabs. The home screen Now Playing
widget is iOS-only for now. See `ios/AudioForge/README.md` for the Xcode setup and the
list of source files.

## Settings

Stored at `%APPDATA%\AudioForge\AudioForge.ini` (on Linux,
`~/.config/AudioForge/AudioForge.ini`): theme, volume, crossfade, equalizer,
music folders, playlists, visible tabs, and integration settings. The
Musixmatch API key is encrypted with Windows DPAPI (tied to your Windows
account); on other platforms it's stored as-is. Video wallpaper assignments
live in `wallpapers.json` next to it, and warnings/errors are logged to
`warnings.log` in the app data folder.
