#pragma once

#include <QString>
#include <QStringList>

namespace audioforge {

// One video assigned to a set of tracks -- picked the same way a playlist
// is built (see PlaylistData in track_info.hpp): pick a video, then check
// off which tracks should use it. No Qt-widget dependency, same tier as
// TrackInfo/PlaylistData.
struct WallpaperEntry
{
    QString id;              // stable identifier (UUID string), generated on creation
    QString videoPath;
    QStringList trackPaths;  // tracks that should use this video
};

} // namespace audioforge
