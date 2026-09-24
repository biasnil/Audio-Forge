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

## Android app (in progress)

`android/` is a Kotlin port of the desktop app, being built in stages:

1. Project setup. **Done.**
2. Playback, library, queue, playlists. **Done:** the phone's music library plus
   added folders, the Tracks/Albums/Artists/Folders/Playlists/Settings tabs,
   background playback with notification and lock-screen controls, and the
   desktop's shuffle, repeat and history logic.
3. Crossfade, equalizer, ReplayGain.
4. Tag and cover editing.
5. Lyrics.
6. Video wallpapers and settings.

Requirements: Android 14+ (minSdk 34), targeting API 36. The app has no native
code, so it runs on both 4 KB and 16 KB memory page size devices.

To build, open the `android/` folder in Android Studio (**File → Open**), let
Gradle sync, then run the `app` configuration. It uses Kotlin, Jetpack Compose,
Gradle 8.14.3 (through the included wrapper) and Android Gradle Plugin 8.11.1.

## Settings

Stored at `%APPDATA%\AudioForge\AudioForge.ini` (on Linux,
`~/.config/AudioForge/AudioForge.ini`): theme, volume, crossfade, equalizer,
music folders, playlists, visible tabs, and integration settings. The
Musixmatch API key is encrypted with Windows DPAPI (tied to your Windows
account); on other platforms it's stored as-is. Video wallpaper assignments
live in `wallpapers.json` next to it, and warnings/errors are logged to
`warnings.log` in the app data folder.
