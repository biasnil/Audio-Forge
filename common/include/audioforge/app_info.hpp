#pragma once

#include <QByteArray>
#include <QString>

namespace audioforge {

// The app's version and its own links (Settings > About, the update check,
// the User-Agent sent to MusicBrainz / LRCLIB / GitHub).
namespace app {

QString Version(); // "1.4.0" -- from project(VERSION) in CMakeLists.txt

inline constexpr const char* kRepoUrl = "https://github.com/biasnil/Audio-Forge";
inline constexpr const char* kSupportEmail = "workacckwanrong@gmail.com";
// Not /releases/latest: that can be an Android release (see update_checker.cpp).
inline constexpr const char* kReleasesApi = "https://api.github.com/repos/biasnil/Audio-Forge/releases?per_page=30";

// MusicBrainz and LRCLIB ask for an identifying User-Agent with a way to
// reach the app's maintainer (a project URL is accepted).
QByteArray UserAgent(); // "AudioForge/1.4.0 ( https://github.com/biasnil/Audio-Forge )"

} // namespace app

} // namespace audioforge
