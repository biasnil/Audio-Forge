cmake_minimum_required(VERSION 3.15)
# The app's version: shown in Settings > About, compared with GitHub releases
# by the update check, and sent in the User-Agent. Desktop releases are tagged
# with this plain number (e.g. "1.4.0").
project(audioforge VERSION 1.4.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)  # Qt's code generator for signals/slots -- picks up
                       # every Q_OBJECT class automatically, including ones
                       # declared in headers (no more "#include foo.moc" trick)
set(CMAKE_AUTORCC ON)  # Qt resource compiler -- picks up resources/icons.qrc
                       # so setWindowIcon() works the same on every platform

# Qt6 -- on Windows, installed via MSYS2's own qt6-base package (matches
# the ucrt64 compiler already in use). Elsewhere, the system packages are
# found through the default search paths.
if(WIN32 AND EXISTS "C:/msys64/ucrt64")
    set(CMAKE_PREFIX_PATH "C:/msys64/ucrt64" ${CMAKE_PREFIX_PATH})
endif()
find_package(Qt6 COMPONENTS Widgets Network Multimedia MultimediaWidgets Svg REQUIRED)

# TagLib reads/writes ID3 tags. It only ships a pkg-config file, not a CMake
# package, so we go through pkg-config.
find_package(PkgConfig REQUIRED)
pkg_check_modules(TAGLIB REQUIRED IMPORTED_TARGET taglib)
# FFmpeg decodes AAC / M4A (podcasts) for the audio engine (common/src/ffmpeg_audio.cpp).
# Qt Multimedia's video backend already ships these DLLs; on Windows they come
# with mingw-w64-ucrt-x86_64-ffmpeg.
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavformat libavcodec libswresample libavutil)

# ONNX Runtime runs genre detection's model (common/src/genre.cpp). On
# Windows: pacman -S mingw-w64-ucrt-x86_64-onnxruntime
find_package(onnxruntime REQUIRED)

# The model is 16 MB -- too big for AUTORCC's generated C++, so it's compiled
# straight into an object file.
qt_add_big_resources(MODEL_RESOURCES resources/models.qrc)

add_executable(audioforge WIN32
    common/src/main.cpp
    common/src/track_info.cpp
    common/src/audio_engine.cpp
    common/src/playback_queue.cpp
    common/src/warnings.cpp
    common/src/discord_presence.cpp
    common/src/secret_store.cpp
    common/src/song_analysis.cpp
    common/src/app_info.cpp
    common/src/translations.cpp
    common/src/update_checker.cpp
    common/src/m3u.cpp
    common/src/visualizer_tap.cpp
    common/src/genre.cpp
    common/src/ffmpeg_audio.cpp

    qt_player/src/track_metadata.cpp
    qt_player/src/track_metadata_tags.cpp
    qt_player/src/lyrics_provider.cpp
    qt_player/src/cover_art_writer.cpp
    qt_player/src/music_library.cpp
    qt_player/src/wallpaper_library.cpp
    qt_player/src/player_window.cpp
    qt_player/src/theme.cpp
    qt_player/src/app_files.cpp
    qt_player/src/song_data.cpp
    qt_player/src/smart_playlists.cpp
    qt_player/src/duplicate_finder.cpp
    qt_player/src/podcasts.cpp
    qt_player/src/visualizer_media.cpp
    qt_player/src/widgets/toggle_switch.cpp
    qt_player/src/widgets/up_next_panel.cpp
    qt_player/src/dialogs/duplicates_dialog.cpp
    qt_player/src/widgets/clickable_widget.cpp
    qt_player/src/widgets/click_seek_slider.cpp
    qt_player/src/widgets/single_page_stacked_widget.cpp
    qt_player/src/widgets/video_background_widget.cpp
    qt_player/src/dialogs/track_list_dialog.cpp
    qt_player/src/dialogs/create_playlist_dialog.cpp
    qt_player/src/dialogs/playlist_edit_dialog.cpp
    qt_player/src/dialogs/musicbrainz_result_dialog.cpp
    qt_player/src/dialogs/manual_tag_dialog.cpp
    qt_player/src/dialogs/wallpaper_track_picker_dialog.cpp
    qt_player/src/dialogs/text_page_dialog.cpp
    qt_player/src/dialogs/smart_playlist_dialog.cpp
    qt_player/src/dialogs/visualizer_media_dialog.cpp
    qt_player/src/widgets/visualizer_widget.cpp
    qt_player/src/widgets/visualizer_style_grid.cpp
    qt_player/src/widgets/card_list_widget.cpp
    qt_player/src/windows_integration.cpp

    # Headers containing Q_OBJECT are listed explicitly so AUTOMOC reliably
    # finds and moc's them -- with the class split across its own .hpp/.cpp
    # pair (rather than one single file like the old qt_main.cpp), relying
    # on transitive #include scanning alone wasn't picking them all up.
    qt_player/include/audioforge/player_window.hpp
    common/include/audioforge/discord_presence.hpp
    qt_player/include/audioforge/widgets/clickable_widget.hpp
    qt_player/include/audioforge/widgets/click_seek_slider.hpp
    qt_player/include/audioforge/widgets/single_page_stacked_widget.hpp
    qt_player/include/audioforge/widgets/video_background_widget.hpp
    qt_player/include/audioforge/dialogs/track_list_dialog.hpp
    qt_player/include/audioforge/dialogs/create_playlist_dialog.hpp
    qt_player/include/audioforge/dialogs/playlist_edit_dialog.hpp
    qt_player/include/audioforge/dialogs/musicbrainz_result_dialog.hpp
    qt_player/include/audioforge/dialogs/manual_tag_dialog.hpp
    qt_player/include/audioforge/dialogs/wallpaper_track_picker_dialog.hpp
    qt_player/include/audioforge/dialogs/duplicates_dialog.hpp
    qt_player/include/audioforge/song_data.hpp
    qt_player/include/audioforge/widgets/up_next_panel.hpp
    common/include/audioforge/update_checker.hpp
    qt_player/include/audioforge/widgets/visualizer_widget.hpp
    qt_player/include/audioforge/widgets/visualizer_style_grid.hpp
    qt_player/include/audioforge/windows_integration.hpp

    # App icon (see resources/README) -- icons.qrc bundles the PNG so
    # setWindowIcon() works cross-platform; app_icon.rc (Windows only,
    # added below) embeds the .ico into the .exe itself.
    resources/icons.qrc
    # Translations (i18n/*.json) and the About pages (about/*.md).
    resources/text.qrc
    ${MODEL_RESOURCES}
)

target_compile_definitions(audioforge PRIVATE AUDIOFORGE_VERSION="${PROJECT_VERSION}")

target_include_directories(audioforge PRIVATE
    common/include
    qt_player/include
    vendor
)

target_link_libraries(audioforge PRIVATE Qt6::Widgets Qt6::Network Qt6::Multimedia Qt6::MultimediaWidgets Qt6::Svg PkgConfig::TAGLIB PkgConfig::FFMPEG onnxruntime::onnxruntime)

if(WIN32)
    # miniaudio (WASAPI device access)
    target_link_libraries(audioforge PRIVATE ole32 winmm)
    # DPAPI (CryptProtectData) for secret_store.cpp
    target_link_libraries(audioforge PRIVATE crypt32)
    # Windows' media controls and the taskbar buttons (windows_integration.cpp)
    target_link_libraries(audioforge PRIVATE runtimeobject shcore shlwapi uuid)
    target_sources(audioforge PRIVATE resources/app_icon.rc)
    # Windows' own, older onnxruntime.dll in System32 is found before the one
    # on PATH; a copy next to the .exe is found first (deploy_windows.sh does
    # the same for the installer).
    add_custom_command(TARGET audioforge POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:onnxruntime::onnxruntime> $<TARGET_FILE_DIR:audioforge>)
elseif(UNIX AND NOT APPLE)
    find_package(Threads REQUIRED)
    target_link_libraries(audioforge PRIVATE Threads::Threads dl m)
elseif(APPLE)
    target_link_libraries(audioforge PRIVATE "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioToolbox")
endif()