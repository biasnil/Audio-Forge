cmake_minimum_required(VERSION 3.15)
project(audioforge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)  # Qt's code generator for signals/slots -- picks up
                       # every Q_OBJECT class automatically, including ones
                       # declared in headers (no more "#include foo.moc" trick)
set(CMAKE_AUTORCC ON)  # Qt resource compiler -- picks up resources/icons.qrc
                       # so setWindowIcon() works the same on every platform

# Qt6 -- installed via MSYS2's own qt6-base package (matches the ucrt64
# compiler already in use).
set(CMAKE_PREFIX_PATH "C:/msys64/ucrt64" ${CMAKE_PREFIX_PATH})
find_package(Qt6 COMPONENTS Widgets Network Multimedia MultimediaWidgets REQUIRED)

# TagLib reads/writes ID3 tags. It only ships a pkg-config file, not a CMake
# package, so we go through pkg-config.
find_package(PkgConfig REQUIRED)
pkg_check_modules(TAGLIB REQUIRED IMPORTED_TARGET taglib)

add_executable(audioforge WIN32
    common/src/main.cpp
    common/src/track_info.cpp
    common/src/audio_engine.cpp
    common/src/playback_queue.cpp
    common/src/warnings.cpp
    common/src/discord_presence.cpp

    qt_player/src/track_metadata.cpp
    qt_player/src/track_metadata_tags.cpp
    qt_player/src/lyrics_provider.cpp
    qt_player/src/cover_art_writer.cpp
    qt_player/src/music_library.cpp
    qt_player/src/wallpaper_library.cpp
    qt_player/src/player_window.cpp
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

    # App icon (see resources/README) -- icons.qrc bundles the PNG so
    # setWindowIcon() works cross-platform; app_icon.rc (Windows only,
    # added below) embeds the .ico into the .exe itself.
    resources/icons.qrc
)

target_include_directories(audioforge PRIVATE
    common/include
    qt_player/include
    vendor
)

target_link_libraries(audioforge PRIVATE Qt6::Widgets Qt6::Network Qt6::Multimedia Qt6::MultimediaWidgets PkgConfig::TAGLIB)

if(WIN32)
    # miniaudio (WASAPI device access)
    target_link_libraries(audioforge PRIVATE ole32 winmm)
    target_sources(audioforge PRIVATE resources/app_icon.rc)
elseif(UNIX AND NOT APPLE)
    find_package(Threads REQUIRED)
    target_link_libraries(audioforge PRIVATE Threads::Threads dl m)
elseif(APPLE)
    target_link_libraries(audioforge PRIVATE "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioToolbox")
endif()