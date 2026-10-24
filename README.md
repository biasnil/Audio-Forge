# AudioForge

A free music player for Android, Windows, Linux and iPhone. No ads, no tracking, no account and no subscriptions.

<p>
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" width="160">
<img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" width="160">
</p>

Download the latest version from the [releases page](https://github.com/biasnil/Audio-Forge/releases). Android releases are tagged `Androidv...`, desktop releases with the plain version number.

## What it does

It plays the music already on your device and lets you organise it: songs, albums, artists, genres, folders and playlists, with crossfade, gapless playback, ReplayGain and volume up to 200%.

- A 10-band equalizer with presets. On Android each output (phone speaker, headphones, car) can keep its own EQ.
- Effects on Android: bass, 3D surround, reverb, stereo width, karaoke (vocal removal and key change), mono and balance.
- Synced lyrics from LRCLIB, Musixmatch or a local .lrc file, with a timing editor and .lrc export on Android.
- A song editor on Android: cut, trim and change the volume on the waveform, then save.
- Tag and cover art editing.
- Smart playlists (for example "genre contains Rock and year is after 2010"), Loved songs, play counts and .m3u import and export.
- A duplicate finder, A-B repeat, sleep timer, playback speed and resume for long tracks.
- A Now Playing visualizer in the cover's colours, or a GIF or looping video of your own behind each song.
- Offline genre detection with Google's YAMNet model.
- Podcasts: search, subscribe and download, or mark any folder as a podcast folder.
- Android: free LibriVox audiobooks from the Internet Archive, and internet radio from Radio Browser.
- Also Android only: home-screen widgets, Android Auto, listening stats with a yearly recap, and key/BPM analysis for beat-matched crossfades and harmonic shuffle.
- Windows only: media keys, the Windows media overlay, taskbar controls and a tray icon.
- Discord Rich Presence on desktop.

The interface is in English, Français, 简体中文, 繁體中文 and Bahasa Melayu.

## Repository layout

```
common/       desktop core: audio engine (miniaudio), queue, lyrics, genre detection, translations
qt_player/    desktop interface (Qt widgets, TagLib)
android/      Android app (Kotlin, Jetpack Compose)
ios/          iPhone app (SwiftUI), see ios/AudioForge/README.md
resources/    icons, translations (i18n/*.json), Terms and Privacy pages, the YAMNet model
scripts/      Windows deploy, installer and translation check scripts
installer/    Inno Setup script for the Windows installer
vendor/       miniaudio.h
```

## Building the desktop app

The desktop version is set in `CMakeLists.txt` (currently 1.4.0).

### Windows (MSYS2 UCRT64)

Install the dependencies from the MSYS2 UCRT64 terminal:

```
pacman -S mingw-w64-ucrt-x86_64-qt6-base mingw-w64-ucrt-x86_64-qt6-tools \
          mingw-w64-ucrt-x86_64-qt6-multimedia \
          mingw-w64-ucrt-x86_64-taglib mingw-w64-ucrt-x86_64-pkgconf \
          mingw-w64-ucrt-x86_64-onnxruntime mingw-w64-ucrt-x86_64-ffmpeg
```

Then in VS Code run CMake: Select a Kit (the MSYS2 UCRT64 GCC kit), CMake: Configure and CMake: Build.

Windows ships an older `onnxruntime.dll` in System32 and loads it before the one on PATH. The build copies MSYS2's copy next to `audioforge.exe` to avoid that. If the wrong one still gets loaded, genre detection reports a failure and everything else works.

To make a folder that runs on a PC without MSYS2 or Qt:

```
scripts/deploy_windows.sh build/audioforge.exe dist
```

It runs `windeployqt6`, adds Qt's FFmpeg media plugin, and keeps copying DLLs from `/ucrt64/bin` until nothing is missing. `windeployqt6` alone isn't enough, because it skips the roughly 80 libraries the FFmpeg plugin needs. Without them the app starts, but video wallpapers don't play and the log says `No QtMultimedia backends found`. To check a folder by hand, run this inside it. It should print nothing:

```
ldd multimedia/ffmpegmediaplugin.dll | grep /ucrt64/bin/
```

### Linux (Debian/Ubuntu)

```
sudo apt install cmake g++ pkg-config qt6-base-dev qt6-multimedia-dev libtag1-dev \
                 gstreamer1.0-plugins-base gstreamer1.0-plugins-good
cmake -B build && cmake --build build
```

Qt Multimedia needs the GStreamer plugins at runtime for video wallpapers.

### Screenshots without clicking through

Set `AUDIOFORGE_SNAPSHOT` to a list of pages and image files, and the app saves them a few seconds after it starts, then quits:

```
QT_QPA_PLATFORM=offscreen AUDIOFORGE_SNAPSHOT="4:playlists.png;nowplaying:np.png" ./audioforge
```

Each page is a tab index or `nowplaying`.

## Building the Android app

Android 12 or newer (minSdk 31), targeting API 36. Open the `android/` folder in Android Studio, let Gradle sync, and run the `app` configuration.

There are two build flavors. `github` has the update check in Settings > About, and `play` leaves it out because Google Play doesn't allow pointing users at updates from elsewhere. Pick one under Build > Select Build Variant.

- GitHub release: run `./gradlew assembleGithubRelease`, tag it `Androidv<versionName>` (for example `Androidv1.4.1`, matching `android/app/build.gradle.kts`) and attach the APK. The update check only looks at `Androidv*` releases.
- Google Play: run `./gradlew bundlePlayRelease`, upload the `.aab`, and raise `versionCode` each time.

To sign release builds, copy `android/keystore.properties.example` to `android/keystore.properties` and fill it in. The changes in each version are in [android/CHANGELOG.md](android/CHANGELOG.md).

The YAMNet model (`app/src/main/assets/yamnet.tflite`) is committed. `android/scripts/fetch_yamnet.sh` downloads it again and checks its SHA-256.

## iPhone app

`ios/AudioForge/` is a SwiftUI app for iOS 17 and newer. See [ios/AudioForge/README.md](ios/AudioForge/README.md) for the Xcode setup.

## Translations

Interface text is written in English inside `tr("...")`. Each `resources/i18n/<code>.json` file maps the English text to a translation, and anything missing shows in English. After changing interface text, run:

```
python scripts/i18n_check.py
```

It lists missing strings, unused entries and translations whose `%1`/`%2` placeholders don't match. `--keys` prints every English string as a template for a new language. Add the new language to `LanguageOptions()` in `common/src/translations.cpp` and to `resources/text.qrc`.

## Where settings are stored

On Windows the desktop app keeps its settings in `%APPDATA%\AudioForge\AudioForge.ini`, and on Linux in `~/.config/AudioForge/AudioForge.ini`. Smart playlists, play counts, detected genres, wallpapers and the other JSON files sit in the same folder, along with `warnings.log`. The Musixmatch API key is encrypted with Windows DPAPI on Windows and stored as-is on Linux.

## License

AudioForge is free software under the [GNU General Public License v3.0](LICENSE). You can use it, change it and share it, as long as what you share stays GPL-3.0 with its source. The libraries it uses keep their own licences, listed in each app's Acknowledgements.
