#pragma once

#include "audioforge/track_info.hpp"

namespace audioforge {

// Reads ID3/tag metadata, audio properties (bitrate/sample rate/channels),
// ReplayGain, and cover art for one file. Falls back to the filename as the
// title if there's no tag (or the file has no title set). Falls back to a
// same-folder image file (cover.jpg, folder.jpg, etc.) for cover art if the
// file has no embedded picture, matching what VLC/Explorer do.
TrackInfo ReadTrackInfo(const QString& path);

// Writes Title/Artist/Album/Year back into the file's tag (used by the
// MusicBrainz tagger). Returns false if the file couldn't be opened/saved.
bool WriteBasicTags(const QString& path, const QString& title, const QString& artist,
                     const QString& album, unsigned int year);

} // namespace audioforge
