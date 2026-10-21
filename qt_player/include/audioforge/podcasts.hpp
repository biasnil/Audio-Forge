#pragma once

#include <QString>
#include <QVector>

#include "audioforge/music_library.hpp"

namespace audioforge {

// The Podcasts tab's shows -- a port of the Android app's Podcasts.kt.
// Episodes are the songs in folders marked Podcast (MusicLibrary::podcasts()).

struct PodcastShow
{
    QString name;
    QVector<TrackInfo> episodes; // newest first (then A-Z by title)
};

// The show an episode belongs to: the first folder below its podcast folder;
// a file right in the podcast folder goes by its Album tag, else the podcast
// folder's own name. Shows A-Z.
QVector<PodcastShow> PodcastShows(const MusicLibrary& library);

// A podcast episode counts as played once it's this close to its end (outros, ads).
inline constexpr float kPodcastPlayedWithinSeconds = 30.0f;

} // namespace audioforge
