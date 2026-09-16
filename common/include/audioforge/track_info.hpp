#pragma once

#include <QString>
#include <QStringList>
#include <QByteArray>

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
    QByteArray coverArt; // raw embedded image bytes (JPEG/PNG), empty if none
};

struct PlaylistData
{
    QString name;
    QStringList trackPaths;
};

// e.g. "MP3  •  320 kb/s  •  44.1 kHz  •  Stereo". Empty string if there's
// no audio-properties data (bitrateKbps <= 0).
QString FormatAudioInfo(const TrackInfo& t);

} // namespace audioforge
