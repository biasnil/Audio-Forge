#pragma once

#include <QString>
#include <QStringList>
#include <QByteArray>

class QFileInfo;

namespace audioforge {

// Plain data about one track. Populated by TrackMetadata::read() (see
// qt_player/audioforge/track_metadata.hpp) -- kept dependency-free here
// (no TagLib, no Qt widgets) so PlaybackQueue and other non-UI code can use
// it without pulling in tag-reading machinery.
struct TrackInfo
{
    QString path;
    QString title;
    QString artist;
    QString album;
    QString genre;
    unsigned int year = 0;
    float replayGainDb = 0.0f; // 0 = no ReplayGain tag found
    int bitrateKbps = 0;
    int sampleRateHz = 0;
    int channels = 0;
    double durationSeconds = 0.0; // 0 = unknown
    qint64 modifiedMs = 0;        // file's last-modified time when these tags were read (library scans skip unchanged files)
    qint64 addedMs = 0;           // when the file arrived on this PC (its creation time), for smart playlists' "Recently Added"
    QByteArray coverArt; // raw image bytes (JPEG/PNG); empty if none OR not loaded --
                         // library scans skip it to save memory, see ReadCoverArt()
};

struct PlaylistData
{
    QString name;
    QStringList trackPaths;
};

// e.g. "MP3  •  320 kb/s  •  44.1 kHz  •  Stereo". Empty string if there's
// no audio-properties data (bitrateKbps <= 0). The format name comes from
// the file extension.
QString FormatAudioInfo(const TrackInfo& t);

// "m:ss" (e.g. "4:03"; "72:15" past an hour).
QString FormatDuration(int seconds);

// TrackInfo::addedMs for a file: its creation time on this PC (copying or
// downloading a song sets it), else its last-modified time.
qint64 FileAddedMs(const QFileInfo& file);

} // namespace audioforge
