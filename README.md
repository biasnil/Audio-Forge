# AudioForge

A Qt-based MP3 player/library manager: playback (with crossfade),
tag-based library browsing (Albums/Tracks/Artists), playlists, ReplayGain,
and MusicBrainz tag lookup.

## Structure

```
audioforge/
├── CMakeLists.txt
├── LICENSE
├── README.md
├── vendor/
│   └── miniaudio.h              # third-party, single-header audio library
│
├── common/                      # no Qt-widget dependency
│   ├── include/audioforge/
│   │   ├── track_info.hpp       # TrackInfo / PlaylistData structs
│   │   ├── audio_engine.hpp     # miniaudio wrapper (playback + crossfade)
│   │   ├── playback_queue.hpp   # shuffle / repeat / history logic
│   │   └── warnings.hpp         # ErrorReporter (warn/info/confirm + log file)
│   └── src/
│       ├── main.cpp             # entry point
│       ├── track_info.cpp
│       ├── audio_engine.cpp
│       ├── playback_queue.cpp
│       └── warnings.cpp
│
└── qt_player/                   # everything Qt-widget/TagLib specific
    ├── include/audioforge/
    │   ├── track_metadata.hpp   # TagLib read/write
    │   ├── music_library.hpp    # folders/tracks/playlists
    │   ├── player_window.hpp    # main window
    │   ├── widgets/clickable_widget.hpp
    │   └── dialogs/
    │       ├── track_list_dialog.hpp
    │       ├── create_playlist_dialog.hpp
    │       ├── playlist_edit_dialog.hpp
    │       └── musicbrainz_result_dialog.hpp
    └── src/  (mirrors the above)
```

## Building (Windows, MSYS2 UCRT64 + VS Code)

Requires (installed via the MSYS2 UCRT64 terminal):
```
pacman -S mingw-w64-ucrt-x86_64-qt6-base mingw-w64-ucrt-x86_64-qt6-tools \
          mingw-w64-ucrt-x86_64-taglib mingw-w64-ucrt-x86_64-pkgconf
```

Then in VS Code: **CMake: Select a Kit** (the MSYS2 UCRT64 GCC kit) →
**CMake: Configure** → **CMake: Build**.

## Settings

Stored at `%APPDATA%\AudioForge\AudioForge.ini`. Warnings/errors are also
logged to `%APPDATA%\AudioForge\warnings.log`.
