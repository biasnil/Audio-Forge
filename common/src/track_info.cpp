#include "audioforge/track_info.hpp"

namespace audioforge {

QString FormatAudioInfo(const TrackInfo& t)
{
    if (t.bitrateKbps <= 0)
    {
        return QString();
    }
    QString channelsText = t.channels == 1 ? "Mono" : t.channels == 2 ? "Stereo" : QString("%1ch").arg(t.channels);
    return QString("MP3  •  %1 kb/s  •  %2 kHz  •  %3")
        .arg(t.bitrateKbps)
        .arg(t.sampleRateHz / 1000.0, 0, 'f', 1)
        .arg(channelsText);
}

} // namespace audioforge
